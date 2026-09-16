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
public class NaverOAuthClient {
    private final AccountProperties.Naver properties;
    private final JsonMapper jsonMapper;
    private final HttpClient httpClient;

    public NaverOAuthClient(AccountProperties properties, JsonMapper jsonMapper) {
        this.properties = properties.naver();
        this.jsonMapper = jsonMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public NaverIdentity authenticate(String code, String state) {
        if (!properties.enabled() || blank(properties.clientId()) || blank(properties.clientSecret())) {
            throw AccountAuthenticationException.unavailable();
        }
        try {
            String form = "grant_type=authorization_code"
                    + "&client_id=" + encode(properties.clientId())
                    + "&client_secret=" + encode(properties.clientSecret())
                    + "&code=" + encode(code)
                    + "&state=" + encode(state);
            HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create(properties.tokenUrl()))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(form))
                    .build();
            HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
            if (tokenResponse.statusCode() / 100 != 2) throw AccountAuthenticationException.invalidCallback();
            JsonNode tokenJson = jsonMapper.readTree(tokenResponse.body());
            String accessToken = text(tokenJson, "access_token");
            if (blank(accessToken)) throw AccountAuthenticationException.invalidCallback();

            HttpRequest profileRequest = HttpRequest.newBuilder(URI.create(properties.userInfoUrl()))
                    .timeout(properties.timeout())
                    .header("Authorization", "Bearer " + accessToken)
                    .GET().build();
            HttpResponse<String> profileResponse = httpClient.send(profileRequest, HttpResponse.BodyHandlers.ofString());
            if (profileResponse.statusCode() / 100 != 2) throw AccountAuthenticationException.invalidCallback();
            JsonNode profile = jsonMapper.readTree(profileResponse.body());
            JsonNode response = profile.path("response");
            String subject = text(response, "id");
            if (blank(subject)) throw AccountAuthenticationException.invalidCallback();
            return new NaverIdentity(subject, text(response, "nickname"));
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

    public record NaverIdentity(String subject, String displayName) { }
}
