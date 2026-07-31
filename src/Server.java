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

    private static final String API_KEY = "O9C2UyYntIPy2XqH";
    private static final String EMAIL = "anubyssanubyss@gmail.com";
    private static final String API_PASSWORD = "Moldova1/.";

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
    private static String currentEvent = "NONE";
    private static double totalProfit = 0.0;
    private static double totalLoss = 0.0;
    private static final double ORDER_SIZE = 13;


    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());

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

                            double[][] candles = getCandles("MINUTE", 101);


                            MarketAnalyzer analyzer = new Server().new MarketAnalyzer();
                            analyzer.analyze(candles);
                            MarketAnalyzer.Signal signal = analyzer.getSignal();

                            //  System.out.println("Trend = " + analyzer.getTrend());
                           //   System.out.println("Resistance = " + analyzer.getResistance());
                           //  System.out.println("Support = " + analyzer.getSupport());
                           //  System.out.println("Strength = " + analyzer.getTrendStrength());
                           // System.out.println("Signal = " + analyzer.getSignal());

                            currentSignal = signal.name();


                            String existingPositions = get("/positions");
                            String openEpic = getOpenEpicInEpics(existingPositions);

                            if (openEpic != null) {
                                currentEpic = openEpic;
                                currentSignal = "MONITORIZEZ POZIȚIA " + currentEpic;
                                monitorOpenPosition();
                                continue;
                            }

                            if (signal == MarketAnalyzer.Signal.WAIT) {

                                currentSignal = "WAIT";
                                Thread.sleep(3000);
                                continue;
                            }

                            String openResult =
                                    openPosition(signal, ORDER_SIZE);

                            System.out.println(
                                    "RĂSPUNS OPEN: " + openResult
                            );

                            String dealReference =
                                    extractJsonValue(
                                            openResult,
                                            "dealReference"
                                    );

                            if (dealReference == null
                                    || dealReference.isBlank()) {

                                currentSignal =
                                        "EROARE OPEN: " + openResult;

                                Thread.sleep(5000);
                                continue;
                            }

                            String confirmResult =
                                    confirmDeal(dealReference);

                            String dealStatus =
                                    extractJsonValue(
                                            confirmResult,
                                            "dealStatus"
                                    );

                            if ("ACCEPTED".equalsIgnoreCase(dealStatus)) {

                                currentSignal =
                                        signal.name()
                                                + " CONFIRMAT "
                                                + currentEpic;

                                currentEvent = signal.name();

                                System.out.println(
                                        "POZIȚIE DESCHISĂ: "
                                                + confirmResult
                                );

                                monitorOpenPosition();

                            } else {

                                String reason =
                                        extractJsonValue(
                                                confirmResult,
                                                "reason"
                                        );

                                currentSignal =
                                        "ORDIN RESPINS: "
                                                + (reason != null
                                                ? reason
                                                : confirmResult);

                                System.out.println(currentSignal);

                                Thread.sleep(5000);
                            }


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



        server.start();

        System.out.println("Server pornit: http://localhost:8080");
    }

    public class MarketAnalyzer {

        public enum Trend {
            UP,
            DOWN,
            SIDEWAYS
        }

        public enum Signal {
            BUY,
            SELL,
            WAIT
        }

        private Trend trend = Trend.SIDEWAYS;

        public Trend getTrend() {
            return trend;
        }

        public double getSupport() {
            return support;
        }

        public double getResistance() {
            return resistance;
        }

        public double getTrendStrength() {
            return trendStrength;
        }

        private double support;
        private double resistance;

        private double trendStrength;

        private Signal signal = Signal.WAIT;

        public void analyze(double[][] candles) {
            trend = detectTrend(candles);
            support = detectSupport(candles);
            resistance = detectResistance(candles);
            trendStrength = calculateTrendStrength(candles);
            signal = generateSignal(candles);
        }

        private Trend detectTrend(double[][] candles) {

            if (candles.length < 20) {
                return Trend.SIDEWAYS;
            }

            int higherHigh = 0;
            int higherLow = 0;

            int lowerHigh = 0;
            int lowerLow = 0;

            for (int i = 1; i < candles.length; i++) {

                double previousHigh = candles[i - 1][0];
                double currentHigh = candles[i][0];

                double previousLow = candles[i - 1][1];
                double currentLow = candles[i][1];

                if (currentHigh > previousHigh) {
                    higherHigh++;
                }

                if (currentLow > previousLow) {
                    higherLow++;
                }

                if (currentHigh < previousHigh) {
                    lowerHigh++;
                }

                if (currentLow < previousLow) {
                    lowerLow++;
                }

            }

            if (higherHigh > lowerHigh && higherLow > lowerLow) {
                return Trend.UP;
            }

            if (lowerHigh > higherHigh && lowerLow > higherLow) {
                return Trend.DOWN;
            }

            return Trend.SIDEWAYS;
        }

        private double detectResistance(double[][] candles) {

            if (candles == null || candles.length < 2) {
                return 0;
            }

            double resistance = -Double.MAX_VALUE;

            // Nu includem ultima lumânare.
            for (int i = 0; i < candles.length - 1; i++) {

                double high = candles[i][0];

                if (high > resistance) {
                    resistance = high;
                }
            }

            return resistance;
        }

        private double detectSupport(double[][] candles) {

            if (candles == null || candles.length < 2) {
                return 0;
            }

            double support = Double.MAX_VALUE;

            // Nu includem ultima lumânare.
            for (int i = 0; i < candles.length - 1; i++) {

                double low = candles[i][1];

                if (low < support) {
                    support = low;
                }
            }

            return support;
        }


        private double calculateTrendStrength(double[][] candles) {

            if (candles.length < 2) {
                return 0;
            }

            double total = 0;

            for (int i = 1; i < candles.length; i++) {

                total += Math.abs(candles[i][2] - candles[i - 1][2]);

            }

            return total;
        }

        private Signal generateSignal(double[][] candles) {

            if (candles == null || candles.length < 20) {
                return Signal.WAIT;
            }

            double firstPrice = candles[0][2];
            double currentPrice = candles[candles.length - 1][2];

            double netMove = Math.abs(currentPrice - firstPrice);

            double efficiency = 0;

            if (trendStrength > 0) {
                efficiency = netMove / trendStrength;
            }

            boolean strongTrend = efficiency >= 0.30;

            if (trend == Trend.UP
                    && currentPrice > firstPrice
                    && strongTrend
                    && currentPrice > resistance) {

                return Signal.BUY;
            }

            if (trend == Trend.DOWN
                    && currentPrice < firstPrice
                    && strongTrend
                    && currentPrice < support) {

                return Signal.SELL;
            }

            return Signal.WAIT;
        }


        public Signal getSignal() {
            return signal;
        }

    } // aici se închide clasa MarketAnalyzer

    private static void monitorOpenPosition() throws Exception {

        while (isBotRunning) {

            currentPnL = getTotalPnLAllPositions();

            double pnl = Double.parseDouble(currentPnL);

            if (pnl >= 50 || pnl <= -0.50) {

                String positions = get("/positions");
                String realDealId =
                        getAnyDealIdInEpics(positions);

                if (realDealId != null
                        && !realDealId.isBlank()) {

                    String closeResult =
                            closePosition(realDealId);

                    if (pnl > 0) {
                        totalProfit += pnl;
                    } else if (pnl < 0) {
                        totalLoss += Math.abs(pnl);
                    }

                    currentEvent = "CLOSED";
                    currentPnL = "0.00";

                    if (totalProfit >= 50.0) {

                        currentSignal =
                                "PROFIT 50 ATINS - REIA ÎN 5 SECUNDE";

                        System.out.println(currentSignal);

                        Thread.sleep(5000);

                        totalProfit = 0.0;
                        totalLoss = 0.0;

                        currentSignal = "REIA ANALIZA";

                    } else {

                        currentSignal =
                                "POZIȚIE ÎNCHISĂ: " + closeResult;
                    }

                    Thread.sleep(3000);
                    break;
                }
            }
        }
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


    private static String closePosition(String dealId) throws Exception {
        return delete("/positions/" + URLEncoder.encode(dealId, StandardCharsets.UTF_8));
    }

    private static String openPosition(
            MarketAnalyzer.Signal signal,
            double size
    ) throws Exception {

        if (signal == MarketAnalyzer.Signal.WAIT) {
            throw new IllegalArgumentException(
                    "Nu deschid poziție pentru semnalul WAIT"
            );
        }

        String body = String.format(
                java.util.Locale.US,
                "{"
                        + "\"epic\":\"%s\","
                        + "\"direction\":\"%s\","
                        + "\"size\":%.2f,"
                        + "\"guaranteedStop\":false"
                        + "}",
                currentEpic,
                signal.name(),
                size
        );

        System.out.println("TRIMIT ORDIN: " + body);

        return post("/positions", body);
    }


    private static String confirmDeal(
            String dealReference
    ) throws Exception {

        String lastResponse = "";

        for (int attempt = 1; attempt <= 5; attempt++) {

            Thread.sleep(1000);

            lastResponse = get(
                    "/confirms/"
                            + URLEncoder.encode(
                            dealReference,
                            StandardCharsets.UTF_8
                    )
            );

            System.out.println(
                    "CONFIRMARE ORDIN " + attempt
                            + ": " + lastResponse
            );

            String dealStatus =
                    extractJsonValue(lastResponse, "dealStatus");

            if (dealStatus != null && !dealStatus.isBlank()) {
                return lastResponse;
            }
        }

        return lastResponse;
    }


    private static String get(String path) throws Exception {
        HttpURLConnection conn = connection(BASE_URL + path, "GET");
        addAuthHeaders(conn);
        return readResponse(conn);
    }

    private static String post(
            String path,
            String body
    ) throws Exception {

        HttpURLConnection conn =
                connection(BASE_URL + path, "POST");

        addAuthHeaders(conn);

        conn.setRequestProperty(
                "Content-Type",
                "application/json"
        );

        conn.setDoOutput(true);

        try (OutputStream os =
                     conn.getOutputStream()) {

            os.write(
                    body.getBytes(
                            StandardCharsets.UTF_8
                    )
            );
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



}