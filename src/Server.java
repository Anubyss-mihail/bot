import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Server {

    private static final String BASE_URL = "https://demo-api-capital.backend-capital.com/api/v1";

    private static final String API_KEY = "vT415Pqw6MXxwZFo";
    private static final String EMAIL = "anubyssanubyss@gmail.com";
    private static final String API_PASSWORD = "Moldova1@.";
    private static final double DEFAULT_SIZE = 0.50;
    private static final String EPIC = "GOLD";
    private static String CST = "";
    private static String SECURITY_TOKEN = "";

    private static boolean isBotRunning = false;
    private static String currentSignal = "Așteptare";
    private static String currentPrice = "--";
    private static String currentPnL = "0.00";

    public static void main(String[] args) throws Exception {

        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);

        server.createContext("/", exchange -> send(exchange, 200, "Server Capital.com API OK"));

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
                        double size = DEFAULT_SIZE;

                        while (isBotRunning) {

                            long wait = millisUntilNext5MinuteCandleClose();

                            Thread.sleep(wait);

                            String side = analyzeMarket();
                            if (side.equals("Așteptare")) {
                                continue;

                            }

                            String openResult = openPosition(side, size);

                            if (openResult == null || openResult.isBlank()) {

                                currentSignal = "EROARE DESCHIDERE POZIȚIE";

                                Thread.sleep(10000);

                                continue;
                            }


                            while (isBotRunning) {

                                currentPnL = getPnL();

                                double pnl = Double.parseDouble(currentPnL);

                                if (pnl >= 5.0 || pnl <= -1.0) {

                                    String positions = get("/positions");

                                    String realDealId = getDealIdForCurrentEpic(positions);

                                    if (realDealId != null && !realDealId.isBlank()) {

                                        closePosition(realDealId);

                                        currentSignal = "Așteptare";

                                        break;

                                    }
                                }


                                Thread.sleep(3000);
                            }
                        }

                    } catch (Exception e) {
                        isBotRunning = false;
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
                String realDealId = getDealIdForCurrentEpic(positions);

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
                currentPrice = getCurrentPrice();
                currentPnL = getPnL();
            } catch (Exception ignored) {
            }

            String json = "{" + "\"status\":\"" + (isBotRunning ? "RUNNING" : "STOP") + "\"," + "\"server\":\"" + (isBotRunning ? "CONECTAT" : "DECONECTAT") + "\"," + "\"platform\":\"Capital.com\"," + "\"symbol\":\"" + EPIC + "\"," + "\"price\":\"" + currentPrice + "\"," + "\"signal\":\"" + currentSignal + "\"," + "\"pnl\":\"" + currentPnL + "\"" + "}";

            sendJson(exchange, 200, json);
        });

        server.createContext("/price", exchange -> {
            try {
                ensureLogin();
                currentPrice = getCurrentPrice();
                send(exchange, 200, currentPrice);
            } catch (Exception e) {
                sendSafe(exchange, 500, "PRICE ERROR: " + e.getMessage());
            }
        });

        server.createContext("/positions", exchange -> {
            try {
                ensureLogin();
                send(exchange, 200, get("/positions"));
            } catch (Exception e) {
                sendSafe(exchange, 500, "ERROR: " + e.getMessage());
            }
        });

        server.createContext("/analyze", exchange -> {
            try {
                ensureLogin();
                String signal = analyzeMarket();
                send(exchange, 200, "Signal: " + signal + "\nPrice: " + currentPrice);
            } catch (Exception e) {
                sendSafe(exchange, 500, "ANALYZE ERROR: " + e.getMessage());
            }
        });

        server.start();

        System.out.println("Server pornit: http://localhost:8080");
    }

    private static String analyzeMarket() throws Exception {

        double emaFast = calculateEMA("MINUTE_5", 51, 25);
        double emaSlow = calculateEMA("MINUTE_5", 51, 51);

        System.out.println("EMA FAST = " + emaFast);
        System.out.println("EMA SLOW = " + emaSlow);

        if (emaFast > emaSlow) {
            currentSignal = "EMA SUS";
            return "BUY";

        } else if (emaFast < emaSlow) {
            currentSignal = "EMA JOS";
            return "SELL";

        } else {
            currentSignal = "EMA AȘTEPTARE";
            return "Așteptare";
        }
    }

    private static double calculateEMA(String resolution, int max, int period) throws Exception {
        String epicEncoded = URLEncoder.encode(EPIC, StandardCharsets.UTF_8);
        String response = get("/prices/" + epicEncoded + "?resolution=" + resolution + "&max=" + max);

        Matcher matcher = closePriceMatcher(response);

        double ema = 0.0;
        double multiplier = 2.0 / (period + 1);

        boolean first = true;

        while (matcher.find()) {
            double bid = Double.parseDouble(matcher.group(1));
            double ask = Double.parseDouble(matcher.group(2));
            double close = (bid + ask) / 2.0;

            if (first) {
                ema = close;
                first = false;
            } else {
                ema = (close - ema) * multiplier + ema;
            }
        }

        return ema;
    }


    private static String getCurrentPrice() throws Exception {
        String epicEncoded = URLEncoder.encode(EPIC, StandardCharsets.UTF_8);
        String response = get("/prices/" + epicEncoded + "?resolution=MINUTE&max=2");


        double price = extractLastCloseMid(response);

        if (price <= 0) {
            return "--";
        }

        return formatPlatformPrice(price);
    }

    private static String getPnL() throws Exception {
        String positions = get("/positions");

        int epicIndex = positions.indexOf("\"epic\":\"" + EPIC + "\"");

        if (epicIndex == -1) {
            return "0.00";
        }

        String beforeEpic = positions.substring(0, epicIndex);

        int positionStart = beforeEpic.lastIndexOf("\"position\"");

        if (positionStart == -1) {
            return "0.00";
        }

        String positionBlock = positions.substring(positionStart, epicIndex);

        double pnl = extractJsonNumber(positionBlock, "upl");

        return format(pnl);
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
        Pattern pattern = Pattern.compile("\"closePrice\"\\s*:\\s*\\{\\s*\"bid\"\\s*:\\s*([0-9.\\-]+)\\s*,\\s*\"ask\"\\s*:\\s*([0-9.\\-]+)");

        return pattern.matcher(json);
    }

    private static double extractJsonNumber(String json, String key) {
        try {
            String pattern = "\"" + key + "\"\\s*:\\s*(-?[0-9]+\\.?[0-9]*)";
            Matcher matcher = Pattern.compile(pattern).matcher(json);

            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }

            return 0.0;
        } catch (Exception e) {
            return 0.0;
        }
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

    private static String getDealIdForCurrentEpic(String positions) {
        int epicIndex = positions.indexOf("\"epic\":\"" + EPIC + "\"");

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
        String body = "{" + "\"identifier\":\"" + EMAIL + "\"," + "\"password\":\"" + API_PASSWORD + "\"," + "\"encryptedPassword\":false" + "}";

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

        String body = "{" + "\"epic\":\"" + EPIC + "\"," + "\"direction\":\"" + direction + "\"," + "\"size\":" + size + "," + "\"orderType\":\"MARKET\"," + "\"guaranteedStop\":false," + "\"currencyCode\":\"USD\"," + "\"forceOpen\":true" + "}";

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

        InputStream stream = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();

        if (stream == null) {
            throw new RuntimeException("HTTP error code: " + code);
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));

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