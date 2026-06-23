import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Server {

    private static final String BASE_URL = "https://demo-api-capital.backend-capital.com/api/v1";

    private static final String API_KEY = "jT7cyanEmBLvvMNI";
    private static final String EMAIL = "anubyssanubyss@gmail.com";
    private static final String API_PASSWORD = "Moldova@1.";

    private static final String[] EPICS = {
            "GOLD",
            "BTCUSD",
            "ETHUSD",
            "XRPUSD",
            "SOLUSD"
    };

    private static String currentEpic = "GOLD";
    private static String CST = "";
    private static String SECURITY_TOKEN = "";

    private static volatile boolean isBotRunning = false;

    private static String currentSignal = "Așteptare";
    private static String currentPrice = "--";
    private static String currentPnL = "0.00";
    private static double currentScore = 0.0;

    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", exchange ->
                send(exchange, 200, "Server Capital.com API OK")
        );

        server.createContext("/start", exchange -> {
            try {
                ensureLogin();

                if (isBotRunning) {
                    send(exchange, 200, "Bot deja ruleaza.");
                    return;
                }

                isBotRunning = true;

                new Thread(() -> {
                    try {
                        while (isBotRunning) {

                            // Dacă există poziție deja deschisă după restart, o monitorizăm.
                            String existingPositions = get("/positions");
                            String openEpic = getOpenEpicInEpics(existingPositions);

                            if (openEpic != null) {
                                currentEpic = openEpic;
                                currentSignal = "MONITORIZEZ POZIȚIA " + currentEpic;
                                monitorOpenPosition();
                                continue;
                            }

                            // Așteaptă închiderea lumânării M5.
                            long wait = millisUntilNext5MinuteCandleClose();
                            currentSignal = "Aștept închiderea lumânării M5...";
                            Thread.sleep(wait);

                            currentSignal = "Analizez piața...";

                            String bestEpic = currentEpic;
                            String bestSide = "Așteptare";
                            double bestScore = 0.0;

                            for (String epic : EPICS) {
                                currentEpic = epic;

                                String signal = analyzeMarket();
                                double score = currentScore;

                                if (!signal.equals("Așteptare") && score > bestScore) {
                                    bestScore = score;
                                    bestEpic = epic;
                                    bestSide = signal;
                                }
                            }

                            currentEpic = bestEpic;

                            if (bestSide.equals("Așteptare")) {
                                continue;
                            }

                            String preOpenPositions = get("/positions");

                            if (hasAnyOpenPositionInEpics(preOpenPositions)) {
                                currentSignal = "EXISTĂ DEJA O POZIȚIE DESCHISĂ - MONITORIZEZ";
                                monitorOpenPosition();
                                continue;
                            }

                            double size = getSizeForEpic(currentEpic);

                            String openResult = openPosition(bestSide, size);

                            if (openResult == null || openResult.isBlank() || !openResult.contains("dealReference")) {
                                currentSignal = "EROARE OPEN: " + openResult;
                                Thread.sleep(10000);
                                continue;
                            }

                            String dealReference = extractJsonValue(openResult, "dealReference");
                            String confirmResult = confirmDeal(dealReference);

                            if (confirmResult == null || !confirmResult.contains("ACCEPTED")) {
                                currentSignal = "ORDIN RESPINS: " + confirmResult;
                                Thread.sleep(10000);
                                continue;
                            }

                            currentSignal = bestSide + " CONFIRMAT " + currentEpic;

                            monitorOpenPosition();
                        }

                    } catch (Exception e) {
                        isBotRunning = false;
                        currentSignal = "AUTO START ERROR: " + e.getMessage();
                        System.out.println("AUTO START ERROR: " + e.getMessage());
                    }
                }).start();

                send(exchange, 200, "Bot pornit. Analizeaza si intra automat.");

            } catch (Exception e) {
                isBotRunning = false;
                sendSafe(exchange, 500, "ERROR: " + e.getMessage());
            }
        });

        server.createContext("/stop", exchange -> {
            try {
                ensureLogin();

                String positions = get("/positions");
                String realDealId = getAnyDealIdInEpics(positions);

                if (realDealId == null || realDealId.isBlank()) {
                    isBotRunning = false;
                    currentSignal = "Așteptare";
                    currentPnL = "0.00";
                    send(exchange, 200, "Nu am gasit nicio pozitie deschisa.");
                    return;
                }

                String result = closePosition(realDealId);

                isBotRunning = false;
                currentSignal = "Așteptare";
                currentPnL = "0.00";

                send(exchange, 200, "Pozitie inchisa: " + result);

            } catch (Exception e) {
                isBotRunning = false;
                sendSafe(exchange, 500, "ERROR: " + e.getMessage());
            }
        });

        server.createContext("/status", exchange -> {
            try {
                ensureLogin();

                String positions = get("/positions");
                String openEpic = getOpenEpicInEpics(positions);

                if (openEpic != null) {
                    currentEpic = openEpic;

                    if (!isBotRunning) {
                        currentSignal = "POZIȚIE DESCHISĂ DUPĂ RESTART";
                    } else if (currentSignal.equals("POZIȚIE DESCHISĂ DUPĂ RESTART")) {
                        currentSignal = "MONITORIZEZ POZIȚIA " + currentEpic;
                    }
                } else if (!isBotRunning) {
                    currentSignal = "Așteptare";
                    currentPnL = "0.00";
                }

                currentPrice = getCurrentPrice();
                currentPnL = getTotalPnLAllPositions();

            } catch (Exception e) {
                currentSignal = "STATUS ERROR: " + e.getMessage();
            }

            String json = "{"
                    + "\"status\":\"" + (isBotRunning ? "RUNNING" : "STOP") + "\","
                    + "\"server\":\"" + (isBotRunning ? "CONECTAT" : "DECONECTAT") + "\","
                    + "\"platform\":\"Capital.com\","
                    + "\"symbol\":\"" + currentEpic + "\","
                    + "\"price\":\"" + currentPrice + "\","
                    + "\"signal\":\"" + currentSignal + "\","
                    + "\"pnl\":\"" + currentPnL + "\""
                    + "}";

            sendJson(exchange, 200, json);
        });

        server.createContext("/price", exchange -> {
            try {
                ensureLogin();

                String oldEpic = currentEpic;
                StringBuilder result = new StringBuilder();

                for (String epic : EPICS) {
                    currentEpic = epic;

                    result.append(epic)
                            .append(" = ")
                            .append(getCurrentPrice())
                            .append("\n");
                }

                currentEpic = oldEpic;

                send(exchange, 200, result.toString());

            } catch (Exception e) {
                sendSafe(exchange, 500, "PRICE ERROR: " + e.getMessage());
            }
        });

        server.createContext("/analyze-fast", exchange -> {
            try {
                ensureLogin();

                String oldEpic = currentEpic;
                StringBuilder result = new StringBuilder();

                for (String epic : EPICS) {
                    currentEpic = epic;

                    String price = getCurrentPrice();
                    String signal = analyzeMarket();

                    result.append(epic)
                            .append(" = ")
                            .append(price)
                            .append(" -> ")
                            .append(signal)
                            .append(" | ")
                            .append(currentSignal)
                            .append("\n");
                }

                currentEpic = oldEpic;

                send(exchange, 200, result.toString());

            } catch (Exception e) {
                sendSafe(exchange, 500, "ANALYZE FAST ERROR: " + e.getMessage());
            }
        });

        server.createContext("/analyze", exchange -> {
            try {
                ensureLogin();

                String oldEpic = currentEpic;
                StringBuilder result = new StringBuilder();

                for (String epic : EPICS) {
                    currentEpic = epic;

                    String signal = analyzeMarket();

                    result.append(epic)
                            .append(" -> ")
                            .append(signal)
                            .append(" | ")
                            .append(currentSignal)
                            .append("\n");
                }

                currentEpic = oldEpic;

                send(exchange, 200, result.toString());

            } catch (Exception e) {
                sendSafe(exchange, 500, "ANALYZE ERROR: " + e.getMessage());
            }
        });

        server.start();

        System.out.println("Server pornit: http://localhost:8080");
    }

    private static void monitorOpenPosition() throws Exception {
        while (isBotRunning) {
            currentPnL = getTotalPnLAllPositions();

            double pnl = Double.parseDouble(currentPnL);

            if (pnl >= 7.0 || pnl <= -3.0) {
                String positions = get("/positions");
                String realDealId = getAnyDealIdInEpics(positions);

                if (realDealId != null && !realDealId.isBlank()) {
                    String closeResult = closePosition(realDealId);

                    currentSignal = "POZIȚIE ÎNCHISĂ: " + closeResult;
                    currentPnL = "0.00";

                    Thread.sleep(3000);
                    break;
                }
            }

            Thread.sleep(3000);
        }
    }

    private static String analyzeMarket() throws Exception {
        currentScore = 0.0;

        double[][] candles = getCandles("MINUTE_5", 100);

        if (candles.length < 100) {
            currentSignal = "LUMÂNĂRI INSUFICIENTE: " + candles.length;
            return "Așteptare";
        }

        double emaFast = calculateEMA(candles, 30);
        double emaSlow = calculateEMA(candles, 61);

        double rsi = calculateRSI(candles, 39);
        double adx = calculateADX(candles, 30);
        double atr = calculateATR(candles, 30);

        double lastPrice = candles[candles.length - 1][2];

        if (lastPrice <= 0) {
            currentSignal = "PREȚ INVALID";
            return "Așteptare";
        }

        double atrPercent = (atr / lastPrice) * 100.0;

        double emaDistance = Math.abs(emaFast - emaSlow);

        if (emaFast > emaSlow && rsi > 65 && adx > 30 && atrPercent > 0.10) {
            currentScore = emaDistance + adx + atrPercent;
            currentSignal = "BUY: EMA + RSI + ADX + ATR%";
            return "BUY";

        } else if (emaFast < emaSlow && rsi < 35 && adx > 30 && atrPercent > 0.10) {
            currentScore = emaDistance + adx + atrPercent;
            currentSignal = "SELL: EMA + RSI + ADX + ATR%";
            return "SELL";

        } else {
            currentSignal =
                    "EMA=" + format(emaFast - emaSlow)
                            + " RSI=" + format(rsi)
                            + " ADX=" + format(adx)
                            + " ATR%=" + format(atrPercent);

            return "Așteptare";
        }
    }

    private static double calculateEMA(double[][] candles, int period) {
        double ema = 0.0;
        double multiplier = 2.0 / (period + 1);
        boolean first = true;

        for (double[] candle : candles) {
            double close = candle[2];

            if (first) {
                ema = close;
                first = false;
            } else {
                ema = (close - ema) * multiplier + ema;
            }
        }

        return ema;
    }

    private static double calculateRSI(double[][] candles, int period) {
        if (candles.length <= period) {
            return 50.0;
        }

        double gain = 0.0;
        double loss = 0.0;

        for (int i = 1; i <= period; i++) {
            double change = candles[i][2] - candles[i - 1][2];

            if (change > 0) {
                gain += change;
            } else {
                loss += Math.abs(change);
            }
        }

        double avgGain = gain / period;
        double avgLoss = loss / period;

        for (int i = period + 1; i < candles.length; i++) {
            double change = candles[i][2] - candles[i - 1][2];

            double currentGain = change > 0 ? change : 0.0;
            double currentLoss = change < 0 ? Math.abs(change) : 0.0;

            avgGain = ((avgGain * (period - 1)) + currentGain) / period;
            avgLoss = ((avgLoss * (period - 1)) + currentLoss) / period;
        }

        if (avgLoss == 0.0) {
            return 100.0;
        }

        double rs = avgGain / avgLoss;

        return 100.0 - (100.0 / (1.0 + rs));
    }

    private static double calculateADX(double[][] candles, int period) {
        if (candles.length < period * 2) {
            return 0.0;
        }

        double smoothedTR = 0.0;
        double smoothedPlusDM = 0.0;
        double smoothedMinusDM = 0.0;

        for (int i = 1; i <= period; i++) {
            double currentHigh = candles[i][0];
            double currentLow = candles[i][1];
            double previousHigh = candles[i - 1][0];
            double previousLow = candles[i - 1][1];
            double previousClose = candles[i - 1][2];

            double upMove = currentHigh - previousHigh;
            double downMove = previousLow - currentLow;

            double plusDM = (upMove > downMove && upMove > 0) ? upMove : 0.0;
            double minusDM = (downMove > upMove && downMove > 0) ? downMove : 0.0;

            double tr = Math.max(
                    currentHigh - currentLow,
                    Math.max(Math.abs(currentHigh - previousClose), Math.abs(currentLow - previousClose))
            );

            smoothedTR += tr;
            smoothedPlusDM += plusDM;
            smoothedMinusDM += minusDM;
        }

        double adx = 0.0;
        int dxCount = 0;

        for (int i = period + 1; i < candles.length; i++) {
            double currentHigh = candles[i][0];
            double currentLow = candles[i][1];
            double previousHigh = candles[i - 1][0];
            double previousLow = candles[i - 1][1];
            double previousClose = candles[i - 1][2];

            double upMove = currentHigh - previousHigh;
            double downMove = previousLow - currentLow;

            double plusDM = (upMove > downMove && upMove > 0) ? upMove : 0.0;
            double minusDM = (downMove > upMove && downMove > 0) ? downMove : 0.0;

            double tr = Math.max(
                    currentHigh - currentLow,
                    Math.max(Math.abs(currentHigh - previousClose), Math.abs(currentLow - previousClose))
            );

            smoothedTR = smoothedTR - (smoothedTR / period) + tr;
            smoothedPlusDM = smoothedPlusDM - (smoothedPlusDM / period) + plusDM;
            smoothedMinusDM = smoothedMinusDM - (smoothedMinusDM / period) + minusDM;

            if (smoothedTR == 0.0) {
                continue;
            }

            double plusDI = 100.0 * (smoothedPlusDM / smoothedTR);
            double minusDI = 100.0 * (smoothedMinusDM / smoothedTR);

            double diSum = plusDI + minusDI;

            if (diSum == 0.0) {
                continue;
            }

            double dx = 100.0 * Math.abs(plusDI - minusDI) / diSum;

            if (dxCount == 0) {
                adx = dx;
            } else {
                adx = ((adx * (period - 1)) + dx) / period;
            }

            dxCount++;
        }

        if (Double.isNaN(adx)) {
            return 0.0;
        }

        return adx;
    }

    private static double calculateATR(double[][] candles, int period) {
        if (candles.length <= period) {
            return 0.0;
        }

        double totalTR = 0.0;

        for (int i = 1; i < candles.length; i++) {
            double high = candles[i][0];
            double low = candles[i][1];
            double prevClose = candles[i - 1][2];

            double tr = Math.max(
                    high - low,
                    Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose))
            );

            totalTR += tr;
        }

        return totalTR / (candles.length - 1);
    }

    private static double[][] getCandles(String resolution, int max) throws Exception {
        String epicEncoded = URLEncoder.encode(currentEpic, StandardCharsets.UTF_8);
        String response = get("/prices/" + epicEncoded + "?resolution=" + resolution + "&max=" + max);

        Pattern pattern = Pattern.compile(
                "\"highPrice\"\\s*:\\s*\\{\\s*\"bid\"\\s*:\\s*([0-9.\\-]+)\\s*,\\s*\"ask\"\\s*:\\s*([0-9.\\-]+).*?"
                        + "\"lowPrice\"\\s*:\\s*\\{\\s*\"bid\"\\s*:\\s*([0-9.\\-]+)\\s*,\\s*\"ask\"\\s*:\\s*([0-9.\\-]+).*?"
                        + "\"closePrice\"\\s*:\\s*\\{\\s*\"bid\"\\s*:\\s*([0-9.\\-]+)\\s*,\\s*\"ask\"\\s*:\\s*([0-9.\\-]+)"
        );

        Matcher matcher = pattern.matcher(response);
        ArrayList<double[]> candles = new ArrayList<>();

        while (matcher.find()) {
            double high = (Double.parseDouble(matcher.group(1)) + Double.parseDouble(matcher.group(2))) / 2.0;
            double low = (Double.parseDouble(matcher.group(3)) + Double.parseDouble(matcher.group(4))) / 2.0;
            double close = (Double.parseDouble(matcher.group(5)) + Double.parseDouble(matcher.group(6))) / 2.0;

            candles.add(new double[]{high, low, close});
        }

        return candles.toArray(new double[0][]);
    }

    private static String getCurrentPrice() throws Exception {
        String epicEncoded = URLEncoder.encode(currentEpic, StandardCharsets.UTF_8);
        String response = get("/prices/" + epicEncoded + "?resolution=MINUTE&max=2");

        double price = extractLastCloseMid(response);

        if (price <= 0) {
            return "--";
        }

        return formatPlatformPrice(price);
    }

    private static String getTotalPnLAllPositions() throws Exception {
        String positions = get("/positions");

        Pattern pattern = Pattern.compile("\"upl\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*)");
        Matcher matcher = pattern.matcher(positions);

        double totalPnl = 0.0;

        while (matcher.find()) {
            totalPnl += Double.parseDouble(matcher.group(1));
        }

        return format(totalPnl);
    }

    private static double extractLastCloseMid(String json) {
        Matcher matcher = closePriceMatcher(json);
        double last = 0.0;

        while (matcher.find()) {
            double bid = Double.parseDouble(matcher.group(1));
            double ask = Double.parseDouble(matcher.group(2));
            last = (bid + ask) / 2.0;
        }

        return last;
    }

    private static Matcher closePriceMatcher(String json) {
        Pattern pattern = Pattern.compile(
                "\"closePrice\"\\s*:\\s*\\{\\s*\"bid\"\\s*:\\s*([0-9.\\-]+)\\s*,\\s*\"ask\"\\s*:\\s*([0-9.\\-]+)"
        );

        return pattern.matcher(json);
    }

    private static String extractJsonValue(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);

        if (start == -1) return null;

        start += pattern.length();
        int end = json.indexOf("\"", start);

        if (end == -1) return null;

        return json.substring(start, end);
    }

    private static String confirmDeal(String dealReference) throws Exception {
        return get("/confirms/" + URLEncoder.encode(dealReference, StandardCharsets.UTF_8));
    }

    private static String getAnyDealIdInEpics(String positions) {
        String oldEpic = currentEpic;

        for (String epic : EPICS) {
            currentEpic = epic;

            String dealId = getDealIdForCurrentEpic(positions);

            if (dealId != null && !dealId.isBlank()) {
                currentEpic = oldEpic;
                return dealId;
            }
        }

        currentEpic = oldEpic;
        return null;
    }

    private static String getOpenEpicInEpics(String positions) {
        for (String epic : EPICS) {
            if (positions.contains("\"epic\":\"" + epic + "\"")) {
                return epic;
            }
        }

        return null;
    }

    private static String getDealIdForCurrentEpic(String positions) {
        int epicIndex = positions.indexOf("\"epic\":\"" + currentEpic + "\"");

        if (epicIndex == -1) {
            return null;
        }

        String beforeEpic = positions.substring(0, epicIndex);
        int positionStart = beforeEpic.lastIndexOf("\"position\"");

        if (positionStart == -1) {
            return null;
        }

        String positionBlock = positions.substring(positionStart, epicIndex);

        return extractJsonValue(positionBlock, "dealId");
    }

    private static boolean hasAnyOpenPositionInEpics(String positions) {
        for (String epic : EPICS) {
            if (positions.contains("\"epic\":\"" + epic + "\"")) {
                return true;
            }
        }

        return false;
    }

    private static double getSizeForEpic(String epic) {
        if (epic.equals("GOLD")) return 0.50;
        if (epic.equals("BTCUSD")) return 0.01;
        if (epic.equals("ETHUSD")) return 0.40;
        if (epic.equals("XRPUSD")) return 600.0;
        if (epic.equals("SOLUSD")) return 9.00;

        return 0.01;
    }

    private static void login() throws Exception {
        String body = "{"
                + "\"identifier\":\"" + EMAIL + "\","
                + "\"password\":\"" + API_PASSWORD + "\","
                + "\"encryptedPassword\":false"
                + "}";

        HttpURLConnection conn = connection(BASE_URL + "/session", "POST");

        conn.setRequestProperty("X-CAP-API-KEY", API_KEY);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        String response = readResponse(conn);

        CST = conn.getHeaderField("CST");
        SECURITY_TOKEN = conn.getHeaderField("X-SECURITY-TOKEN");

        if (CST == null || SECURITY_TOKEN == null) {
            throw new RuntimeException("Nu am primit CST / X-SECURITY-TOKEN. Raspuns: " + response);
        }
    }

    private static void ensureLogin() throws Exception {
        if (CST == null || CST.isBlank() || SECURITY_TOKEN == null || SECURITY_TOKEN.isBlank()) {
            login();
        }
    }

    private static String openPosition(String side, double size) throws Exception {
        String direction = side.equals("SELL") ? "SELL" : "BUY";

        String body = "{"
                + "\"epic\":\"" + currentEpic + "\","
                + "\"direction\":\"" + direction + "\","
                + "\"size\":" + size + ","
                + "\"orderType\":\"MARKET\","
                + "\"guaranteedStop\":false,"
                + "\"currencyCode\":\"USD\","
                + "\"forceOpen\":false"
                + "}";

        return post("/positions", body);
    }

    private static String closePosition(String dealId) throws Exception {
        return delete("/positions/" + URLEncoder.encode(dealId, StandardCharsets.UTF_8));
    }

    private static String get(String path) throws Exception {
        HttpURLConnection conn = connection(BASE_URL + path, "GET");
        addAuthHeaders(conn);
        return readResponse(conn);
    }

    private static String post(String path, String body) throws Exception {
        HttpURLConnection conn = connection(BASE_URL + path, "POST");
        addAuthHeaders(conn);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);

        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }

        return readResponse(conn);
    }

    private static String delete(String path) throws Exception {
        HttpURLConnection conn = connection(BASE_URL + path, "DELETE");
        addAuthHeaders(conn);
        return readResponse(conn);
    }

    private static HttpURLConnection connection(String url, String method) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        return conn;
    }

    private static void addAuthHeaders(HttpURLConnection conn) {
        conn.setRequestProperty("CST", CST);
        conn.setRequestProperty("X-SECURITY-TOKEN", SECURITY_TOKEN);
        conn.setRequestProperty("X-CAP-API-KEY", API_KEY);
    }

    private static String readResponse(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();

        InputStream stream = (code >= 200 && code < 300)
                ? conn.getInputStream()
                : conn.getErrorStream();

        if (stream == null) {
            throw new RuntimeException("HTTP error code: " + code);
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)
        );

        StringBuilder result = new StringBuilder();
        String line;

        while ((line = reader.readLine()) != null) {
            result.append(line);
        }

        reader.close();

        return result.toString();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.US, "%.4f", value);
    }

    private static String formatPlatformPrice(double value) {
        return String.format(java.util.Locale.US, "%,.2f", value);
    }

    private static void send(HttpExchange exchange, int code, String response) throws IOException {
        byte[] data = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(code, data.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private static void sendJson(HttpExchange exchange, int code, String response) throws IOException {
        byte[] data = response.getBytes(StandardCharsets.UTF_8);

        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.sendResponseHeaders(code, data.length);

        try (OutputStream os = exchange.getResponseBody()) {
            os.write(data);
        }
    }

    private static void sendSafe(HttpExchange exchange, int code, String response) {
        try {
            send(exchange, code, response);
        } catch (IOException ignored) {
        }
    }

    private static long millisUntilNext5MinuteCandleClose() {
        long now = System.currentTimeMillis();
        long fiveMinutes = 5 * 60 * 1000;
        long nextClose = ((now / fiveMinutes) + 1) * fiveMinutes;

        return nextClose - now + 2000;
    }
}