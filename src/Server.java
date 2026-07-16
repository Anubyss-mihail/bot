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

    private static final String API_KEY = "fq3dOSfe3zSXgy2e";
    private static final String EMAIL = "anubyssanubyss@gmail.com";
    private static final String API_PASSWORD = "Moldova123.";

    private static final String[] EPICS = {
            "GOLD",
            //"BTCUSD",
           // "ETHUSD"
    };

    private static String currentEpic = "GOLD";
    private static String CST = "";
    private static String SECURITY_TOKEN = "";

    private static volatile boolean isBotRunning = false;

    private static String currentSignal = "Așteptare";
    private static String currentPrice = "--";
    private static String currentPnL = "0.00";
    private static double currentScore = 0.0;
    private static String currentEvent = "NONE";
    private static double totalProfit = 0.0;
    private static double totalLoss = 0.0;

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

                    while (isBotRunning) {

                        try {

                            ensureLogin();

                            String existingPositions = get("/positions");
                            String openEpic = getOpenEpicInEpics(existingPositions);

                            if (openEpic != null) {
                                currentEpic = openEpic;
                                currentSignal = "MONITORIZEZ POZIȚIA " + currentEpic;
                                monitorOpenPosition();
                                continue;
                            }

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
                            currentEvent = bestSide;

                            monitorOpenPosition();

                        } catch (Exception e) {

                            currentSignal = "EROARE INTERNET/API: " + e.getMessage()
                                    + " | Reîncerc în 10 secunde...";

                            System.out.println(currentSignal);

                            CST = "";
                            SECURITY_TOKEN = "";

                            try {
                                Thread.sleep(10000);
                            } catch (InterruptedException ignored) {
                            }
                        }
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
                    + "\"pnl\":\"" + currentPnL + "\","
                    + "\"event\":\"" + currentEvent + "\","
                    + "\"profit\":\"" + format(totalProfit) + "\","
                    + "\"loss\":\"" + format(totalLoss) + "\""
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


            if (pnl >= 50 || pnl <= -0.50) {
                String positions = get("/positions");
                String realDealId = getAnyDealIdInEpics(positions);

                if (realDealId != null && !realDealId.isBlank()) {
                    String closeResult = closePosition(realDealId);

                    if (pnl > 0) {
                        totalProfit += pnl;
                    } else if (pnl < 0) {
                        totalLoss += Math.abs(pnl);
                    }

                    currentSignal = "POZIȚIE ÎNCHISĂ: " + closeResult;
                    currentEvent = "CLOSED";
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

        // M5 arată direcția principală.
        double[][] candlesM5 = getCandles("MINUTE_5", 46);

        // M1 confirmă momentul intrării.
        double[][] candlesM1 = getCandles("MINUTE", 46);

        if (candlesM5.length < 45) {
            currentSignal = "LUMÂNĂRI INSUFICIENTE M5";
            return "Așteptare";
        }

        if (candlesM1.length < 45) {
            currentSignal = "LUMÂNĂRI INSUFICIENTE M1";
            return "Așteptare";
        }

        // Calculează EMA 11 și EMA 31 pe M5.
        double emaFastM5 = calculateEMA(candlesM5, 11);
        double emaSlowM5 = calculateEMA(candlesM5, 31);

        // Calculează EMA 11 și EMA 31 pe M1.
        double emaFastM1 = calculateEMA(candlesM1, 11);
        double emaSlowM1 = calculateEMA(candlesM1, 31);

        /*
         * calculateEMA() din codul tău tratează poziția 0
         * ca fiind lumânarea cea mai nouă.
         */
        double lastPriceM5 = candlesM5[0][2];
        double lastPriceM1 = candlesM1[0][2];

        if (lastPriceM5 <= 0 || lastPriceM1 <= 0) {
            currentSignal = "PREȚ INVALID";
            return "Așteptare";
        }

        // Distanța dintre EMA-uri.
        double diferentaM5 = Math.abs(emaFastM5 - emaSlowM5);
        double diferentaM1 = Math.abs(emaFastM1 - emaSlowM1);

        /*
         * Filtru pentru piața laterală.
         * Pragurile se adaptează automat la preț.
         */
        double pragM5 = lastPriceM5 * 0.00005;
        double pragM1 = lastPriceM1 * 0.00002;

        currentScore = diferentaM5 + diferentaM1;

        // Nu intră dacă EMA-urile sunt prea apropiate.
        if (diferentaM5 < pragM5) {
            currentSignal = "AȘTEPTARE: M5 FĂRĂ TREND CLAR";
            return "Așteptare";
        }

        if (diferentaM1 < pragM1) {
            currentSignal = "AȘTEPTARE: M1 FĂRĂ CONFIRMARE";
            return "Așteptare";
        }

        /*
         * BUY numai când M5 și M1 confirmă împreună
         * direcția în sus.
         */
        if (emaFastM5 > emaSlowM5 &&
                emaFastM1 > emaSlowM1 &&
                lastPriceM1 > emaFastM1) {

            currentSignal =
                    "BUY: M5 SUS + M1 SUS"
                            + " | EMA11 M5=" + format(emaFastM5)
                            + " | EMA31 M5=" + format(emaSlowM5);

            return "BUY";
        }

        /*
         * SELL numai când M5 și M1 confirmă împreună
         * direcția în jos.
         */
        if (emaFastM5 < emaSlowM5 &&
                emaFastM1 < emaSlowM1 &&
                lastPriceM1 < emaFastM1) {

            currentSignal =
                    "SELL: M5 JOS + M1 JOS"
                            + " | EMA11 M5=" + format(emaFastM5)
                            + " | EMA31 M5=" + format(emaSlowM5);

            return "SELL";
        }

        currentSignal = "AȘTEPTARE: M5 ȘI M1 NU CONFIRMĂ";
        return "Așteptare";
    }


// indecator ema
private static double calculateEMA(double[][] candles, int period) {

    if (candles == null || candles.length < period) {
        return 0.0;
    }

    double multiplier = 2.0 / (period + 1);

    int start = candles.length - 1;
    int end = candles.length - period;

    double sma = 0.0;

    for (int i = start; i >= end; i--) {
        sma += candles[i][2];
    }

    sma /= period;

    double ema = sma;

    for (int i = end - 1; i >= 0; i--) {
        double close = candles[i][2];
        ema = ((close - ema) * multiplier) + ema;
    }

    return ema;
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
        if (epic.equals("GOLD")) return 1.3;
        //if (epic.equals("BTCUSD")) return 0.01;
       //if (epic.equals("ETHUSD")) return 0.40;

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
                + "\"forceOpen\":true"
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
        conn.setConnectTimeout(30000);
        conn.setReadTimeout(30000);
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