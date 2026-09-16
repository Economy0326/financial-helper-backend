package com.financialhelper.account;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
public class KakaoOAuthClient {
    private final AccountProperties properties;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public KakaoOAuthClient(AccountProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        Duration timeout = properties.kakao().timeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public KakaoIdentity authenticate(String code) {
        if (!properties.kakao().enabled() || blank(properties.kakao().clientId())
                || blank(properties.kakao().clientSecret())) {
            throw AccountAuthenticationException.unavailable();
        }
        try {
            String form = "grant_type=authorization_code"
                    + "&client_id=" + encode(properties.kakao().clientId())
                    + "&client_secret=" + encode(properties.kakao().clientSecret())
                    + "&redirect_uri=" + encode(properties.kakao().redirectUri())
                    + "&code=" + encode(code);
            HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create(properties.kakao().tokenUrl()))
                    .timeout(properties.kakao().timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (tokenResponse.statusCode() / 100 != 2) throw AccountAuthenticationException.invalidCallback();
            JsonNode tokenJson = jsonMapper.readTree(tokenResponse.body());
            String accessToken = text(tokenJson, "access_token");
            if (blank(accessToken)) throw AccountAuthenticationException.invalidCallback();

            HttpRequest profileRequest = HttpRequest.newBuilder(URI.create(properties.kakao().userInfoUrl()))
                    .timeout(properties.kakao().timeout())
                    .header("Authorization", "Bearer " + accessToken)
                    .GET().build();
            HttpResponse<String> profileResponse = httpClient.send(profileRequest, HttpResponse.BodyHandlers.ofString());
            if (profileResponse.statusCode() / 100 != 2) throw AccountAuthenticationException.invalidCallback();
            JsonNode profile = jsonMapper.readTree(profileResponse.body());
            String subject = text(profile, "id");
            if (blank(subject)) throw AccountAuthenticationException.invalidCallback();
            JsonNode propertiesNode = profile.path("properties");
            String nickname = text(propertiesNode, "nickname");
            return new KakaoIdentity(subject, nickname);
        } catch (AccountAuthenticationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AccountAuthenticationException("SOCIAL_LOGIN_PROVIDER_ERROR",
                    "로그인 제공자와 통신할 수 없습니다.",
                    org.springframework.http.HttpStatus.BAD_GATEWAY);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    public record KakaoIdentity(String subject, String displayName) { }
}
