package com.stream.tool;

/**
 * @author 周子斐
 * @date 2023/2/17
 * @Description
 */

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.prefs.Preferences;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.sun.deploy.net.HttpRequest;
import com.sun.jna.platform.win32.Advapi32Util;
import com.sun.jna.platform.win32.WinReg;
import com.sun.xml.internal.ws.util.StreamUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.omg.IOP.ENCODING_CDR_ENCAPS;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;
import sun.misc.IOUtils;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Main {

    private static final ReentrantLock lock = new ReentrantLock();
    private static AtomicInteger retry = new AtomicInteger(3);

    private static final List<String> URL_LIST = Arrays.asList("https://cdn.jsdelivr.net/gh/%s@%s/%s", "https://ghproxy.com/https://raw.githubusercontent.com/%s/%s/%s");

    private static String repo = "wxy1343/ManifestAutoUpdate";
    private static String steamPath;
    private static final int THREAD_POOL_SIZE = 32;
    private static final Object LOCK = new Object();

    public static void main(String[] args) {
        System.out.println("app_id:");
        final Scanner scanner = new Scanner(System.in);
        String appId = scanner.nextLine();
        String repo = "wxy1343/ManifestAutoUpdate";
        String url = String.format("https://api.github.com/repos/%s/branches/%s", repo, appId);
        JSONObject response = null;
        try {
            final String get = HttpUtils.Get(url);
            response = JSONObject.parseObject(get);
        } catch (Exception ex) {
            System.out.printf("入库失败: %s%n", appId);
            return;
        }
        if (response.containsKey("commit")) {
            String sha = response.getJSONObject("commit").getString("sha");
            String tree_url = response.getJSONObject("commit").getJSONObject("commit").getJSONObject("tree").getString("url");
            JSONObject tree_response = null;
            try {
                tree_response = JSONObject.parseObject(HttpUtils.Get(tree_url));
            } catch (Exception ex) {
                System.out.printf("入库失败: %s%n", appId);
                return;
            }
            if (tree_response.containsKey("tree")) {
                List<Future<Boolean>> results = new ArrayList<>();
                ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
                for (int i = 0; i < tree_response.getJSONArray("tree").size(); i++) {
                    JSONObject tree_item = tree_response.getJSONArray("tree").getJSONObject(i);
                    results.add(executor.submit(() -> getManifest(sha, tree_item.getString("path"), getSteamPath(), Integer.valueOf(appId))));
                }
                executor.shutdown();
                try {
                    executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
                } catch (InterruptedException ex) {
                    synchronized (LOCK) {
                        executor.shutdownNow();
                    }
                    throw new RuntimeException(ex);
                }
                if (results.stream().allMatch(Future::isDone)) {
                    if (results.stream().allMatch(result -> {
                        try {
                            return result.get();
                        } catch (Exception ex) {
                            return false;
                        }
                    })) {
                        System.out.printf("入库成功: %s%n", appId);
                        System.out.println("重启steam生效");
                        return;
                    }
                }
            }
        }
        System.out.printf("入库失败: %s%n", appId);
    }

    public static boolean getManifest(String sha, String path, Path steamPath, Integer appId) {
        try {
            if (path.endsWith(".manifest")) {
                Path depotCachePath = steamPath.resolve("depotcache");
                synchronized (lock) {
                    if (!Files.exists(depotCachePath)) {
                        Files.createDirectories(depotCachePath);
                    }
                }
                Path savePath = depotCachePath.resolve(path);
                if (Files.exists(savePath)) {
                    synchronized (lock) {
                        System.out.println("已存在清单: " + path);
                    }
                    return true;
                }
                byte[] content = get(sha, path);
                synchronized (lock) {
                    System.out.println("清单下载成功: " + path);
                }
                Files.write(savePath, content);
            } else if (path.equals("config.vdf")) {
                byte[] content = get(sha, path);
                synchronized (lock) {
                    System.out.println("密钥下载成功: " + path);
                }
                String str = new String(content);
                Map<String, Object> depotsConfig = JSONObject.parseObject(str);
                if (depotkeyMerge(steamPath.resolve("config").resolve(path), depotsConfig)) {
                    System.out.println("合并config.vdf成功");
                }
                final Object depots = depotsConfig.get("depots");
                Map<String, Map<String, String>> entrySet = (Map<String, Map<String, String>>) depots;
                final Set<Map.Entry<String, Map<String, String>>> entries = entrySet.entrySet();
                final ArrayList<Map<String, String>> maps = new ArrayList<>();
                for (Map.Entry<String, Map<String, String>> entry : entries) {
                    final String key = entry.getKey();
                    final Map<String, String> value = entry.getValue();
                    final String decryptionKey = value.get("DecryptionKey");
                    final HashMap<String, String> stringStringHashMap = new HashMap<>();
                    stringStringHashMap.put("depot_id", key);
                    stringStringHashMap.put("depot_key", decryptionKey);
                    maps.add(stringStringHashMap);
                }
                if (stoolAdd(maps)) {
                    System.out.println("导入steamtools成功");
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    private static byte[] get(String sha, String path) throws Exception {
        while (true) {
            for (String urlTemplate : URL_LIST) {
                String url = String.format(urlTemplate, repo, sha, path);
                try {
                    HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    if (conn.getResponseCode() == 200) {
                        final InputStream inputStream = conn.getInputStream();
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        byte[] buffer = new byte[1024 * 4];
                        int n = 0;
                        while (-1 != (n = inputStream.read(buffer))) {
                            output.write(buffer, 0, n);
                        }
                        return output.toByteArray();
                    }
                } catch (IOException e) {
                    System.out.printf("获取失败: %s\n", path);
                    retry.getAndDecrement();
                    ;
                    if (retry.get() == 0) {
                        System.out.printf("超过最大重试次数: %s\n", path);
                        throw e;
                    }
                }
            }
        }
    }

    private static boolean depotkeyMerge(Path path, Map<String, Object> depotsConfig) {
        final File file = path.toFile();
        synchronized (lock) {
            if (!file.exists()) {
                System.out.println("config.vdf不存在");
                return false;
            }
        }
        try {
            final String filePath = file.getPath();
            final Map<String, Object> config = VdfParser.parse(filePath);
            Map<String, Object> installConfigStore = (Map<String, Object>) config.get("InstallConfigStore");
            Map<String, Object> software = (Map<String, Object>) installConfigStore.get("Software");
            Map<String, Object> valve = (Map<String, Object>) software.getOrDefault("Valve", software.get("valve"));
            Map<String, Object> steam = (Map<String, Object>) valve.getOrDefault("Steam", valve.get("steam"));
            Map<String, Object> depots = (Map<String, Object>) steam.getOrDefault("depots", Map.of());
            depots.putAll((Map<String, Object>) depotsConfig.get("depots"));
            VdfParser.dump(config,filePath);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean stoolAdd(List<Map<String, String>> depotList) {
        Path infoPath = Paths.get(System.getProperty("user.home"), "AppData", "Roaming", "Stool", "info.pak");
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + infoPath); Statement stmt = conn.createStatement()) {
            for (Map<String, String> depot : depotList) {
                String depotId = depot.get("depot_id");
                String type = depot.get("type");
                String depotKey = depot.get("depot_key");
                String sql = String.format("INSERT OR REPLACE INTO Appinfo (appid, type, DecryptionKey) VALUES (%s, '%s', %s);", depotId, type, depotKey);
                stmt.executeUpdate(sql);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }

    public static String parseInputStream(InputStream in) throws IOException {
        String result = "";
        StringBuilder content = null;
        if (null != in) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in));
            content = new StringBuilder();
            String line = "";
            while ((line = r.readLine()) != null) {
                content.append(line);
            }
            result = content.toString();
        }
        return result;
    }

    private static Path getSteamPath() {
        // implementation of getSteamPath method
        String steamPath = Advapi32Util.registryGetStringValue(
                WinReg.HKEY_CURRENT_USER, "Software\\Valve\\Steam", "SteamPath");
        if (steamPath != null) {
            return Paths.get(steamPath);
        } else {
            return null;
        }
    }

    private static boolean getConfigVdf(String sha, String path) {
        return false;
    }

    private static boolean getManifest(String sha, String path, String app_id) {
        return false;
    }

    private static JSONObject getJson(String branchUrl) {
        final String get = HttpUtils.Get(branchUrl);
        return JSONObject.parseObject(get);
    }
//        private static boolean stoolAdd(String depot_id, String type, String depot_key) {
//            try {
//                String infoPath = steamPath + "/Stool/info.pak";
//                SQLiteDataSource dataSource = new SQLiteDataSource();
//                SQLiteConfig config = new SQLiteConfig();
//                config.setReadOnly(false);
//                config.setCacheSize(8000);
//                dataSource.setConfig(config);
//                dataSource.setUrl("jdbc:sqlite:" + infoPath);
//                try (var conn = dataSource.getConnection()) {
//                    var statement = conn.createStatement();
//                    var sql = String.format("INSERT OR REPLACE INTO Appinfo (appid,
//
//                }
}
