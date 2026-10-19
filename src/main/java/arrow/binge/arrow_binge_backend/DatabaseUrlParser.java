package arrow.binge.arrow_binge_backend;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

public final class DatabaseUrlParser {
    public static Map<String, String> parse(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return Map.of();
        }
        rawUrl = rawUrl.trim();
        if (rawUrl.startsWith("jdbc:")) {
            return Map.of("url", rawUrl);
        }
        if (!rawUrl.startsWith("postgres://") && !rawUrl.startsWith("postgresql://")) {
            return Map.of("url", rawUrl);
        }
        try {
            String normalized = rawUrl.replaceFirst("^(postgres|postgresql)://", "http://");
            URI uri = URI.create(normalized);
            Map<String, String> result = new HashMap<>();

            String userInfo = uri.getUserInfo();
            if (userInfo != null && !userInfo.isBlank()) {
                String[] parts = userInfo.split(":", 2);
                result.put("username", parts[0]);
                if (parts.length > 1) {
                    result.put("password", parts[1]);
                }
            }

            int port = uri.getPort() > 0 ? uri.getPort() : 5432;
            String path = uri.getPath() != null && !uri.getPath().isBlank() ? uri.getPath() : "/arrow_binge";
            String query = uri.getQuery() != null && !uri.getQuery().isBlank() ? "?" + uri.getQuery() : "";

            String jdbcUrl = "jdbc:postgresql://" + uri.getHost() + ":" + port + path + query;
            result.put("url", jdbcUrl);
            return result;
        } catch (Exception e) {
            return Map.of("url", rawUrl);
        }
    }
}
