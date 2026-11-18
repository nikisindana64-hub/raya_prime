package com.mamarala.rayaprime.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.config.RayaConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Низкоуровневый HTTP-клиент Gemini API (текст/аудио → JSON, текст → речь). Вызывать ТОЛЬКО из фонового потока. */
public final class GeminiClient {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Gson GSON = new Gson();
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";

    private GeminiClient() {}

    public static class GeminiException extends Exception {
        public final int status;

        public GeminiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** Один запрос generateContent. Возвращает тело ответа как JsonObject. */
    private static JsonObject post(String model, String apiKey, JsonObject payload, int timeoutSec) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE + model + ":generateContent"))
                .header("Content-Type", "application/json; charset=UTF-8")
                .header("x-goog-api-key", apiKey)
                .timeout(Duration.ofSeconds(timeoutSec))
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            String body = response.body();
            if (body != null && body.length() > 400) body = body.substring(0, 400) + "…";
            throw new GeminiException(response.statusCode(), "HTTP " + response.statusCode() + " (" + model + "): " + body);
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static List<String> modelChain(String primary, String fallbackCsv) {
        List<String> chain = new ArrayList<>();
        chain.add(primary);
        for (String m : RayaConfig.splitList(fallbackCsv)) {
            if (!chain.contains(m)) chain.add(m);
        }
        return chain;
    }

    // ------------------------------------------------------------------ мозг

    /** Запрашивает у Gemini JSON-решение. При 404/400 (модель снята / нет параметра) пробует запасные модели. */
    public static String generateJson(String apiKey, JsonObject payloadTemplate) throws Exception {
        String thinking = RayaConfig.GEMINI_THINKING_LEVEL.get().trim();
        StringBuilder errors = new StringBuilder();
        int lastStatus = 0;
        for (String model : modelChain(RayaConfig.GEMINI_MODEL.get().trim(), RayaConfig.GEMINI_FALLBACK_MODELS.get())) {
            boolean useThinking = !thinking.isEmpty() && model.startsWith("gemini-3");
            boolean withThinking = useThinking;
            for (int attempt = 0; attempt < 3; attempt++) {
                JsonObject payload = payloadTemplate.deepCopy();
                if (withThinking) {
                    JsonObject tc = new JsonObject();
                    tc.addProperty("thinkingLevel", thinking);
                    payload.getAsJsonObject("generationConfig").add("thinkingConfig", tc);
                }
                try {
                    return extractText(post(model, apiKey, payload, 40));
                } catch (GeminiException e) {
                    lastStatus = e.status;
                    RayaPrimeMod.LOGGER.warn("[RayaPrime] {} (попытка {}, thinking={}): {}", model, attempt + 1, withThinking, e.getMessage());
                    if (e.status == 400 && withThinking) {
                        withThinking = false; // возможно, модель не принимает thinkingConfig — повтор без него
                        continue;
                    }
                    if ((e.status == 503 || e.status == 500 || e.status == 429) && attempt < 2) {
                        Thread.sleep(1500L * (attempt + 1)); // перегрузка — подождать и повторить ту же модель
                        continue;
                    }
                    String m = e.getMessage();
                    if (m.length() > 170) m = m.substring(0, 170) + "…";
                    errors.append("\n• ").append(m.replace('\n', ' '));
                    break;
                }
            }
            // 401/403 — проблема с ключом, менять модель бессмысленно
            if (lastStatus == 401 || lastStatus == 403) {
                break;
            }
        }
        throw new GeminiException(lastStatus, errors.length() == 0 ? "Нет доступных моделей Gemini" : errors.toString());
    }

    private static String extractText(JsonObject resp) {
        JsonArray candidates = resp.has("candidates") && resp.get("candidates").isJsonArray() ? resp.getAsJsonArray("candidates") : null;
        if (candidates == null || candidates.isEmpty()) {
            String reason = "";
            if (resp.has("promptFeedback")) reason = " " + resp.get("promptFeedback");
            throw new IllegalStateException("Пустой ответ Gemini." + reason);
        }
        JsonObject content = candidates.get(0).getAsJsonObject().getAsJsonObject("content");
        if (content == null || !content.has("parts")) {
            throw new IllegalStateException("Gemini не вернул текст (возможно, сработал фильтр безопасности).");
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement p : content.getAsJsonArray("parts")) {
            JsonObject po = p.getAsJsonObject();
            // части с "thought": true — это размышления, их пропускаем
            if (po.has("thought") && po.get("thought").getAsBoolean()) continue;
            if (po.has("text")) sb.append(po.get("text").getAsString());
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ голос (TTS)

    /**
     * Синтез речи Gemini TTS. Возвращает PCM 48 кГц 16 бит моно (готово для Simple Voice Chat) или null при неудаче.
     */
    public static short[] synthesizeSpeech48k(String apiKey, String text) {
        String voice = RayaConfig.TTS_VOICE.get().trim();
        for (String model : modelChain(RayaConfig.TTS_MODEL.get().trim(), RayaConfig.TTS_FALLBACK_MODELS.get())) {
            try {
                JsonObject payload = new JsonObject();
                JsonArray contents = new JsonArray();
                JsonObject c = new JsonObject();
                JsonArray parts = new JsonArray();
                JsonObject tp = new JsonObject();
                tp.addProperty("text", "Скажи дружелюбно, живо и немного иронично, как умная ИИ-помощница: " + text);
                parts.add(tp);
                c.add("parts", parts);
                contents.add(c);
                payload.add("contents", contents);

                JsonObject gen = new JsonObject();
                JsonArray modalities = new JsonArray();
                modalities.add("AUDIO");
                gen.add("responseModalities", modalities);
                JsonObject prebuilt = new JsonObject();
                prebuilt.addProperty("voiceName", voice);
                JsonObject voiceCfg = new JsonObject();
                voiceCfg.add("prebuiltVoiceConfig", prebuilt);
                JsonObject speechCfg = new JsonObject();
                speechCfg.add("voiceConfig", voiceCfg);
                gen.add("speechConfig", speechCfg);
                payload.add("generationConfig", gen);

                JsonObject resp = post(model, apiKey, payload, 40);
                JsonArray cand = resp.getAsJsonArray("candidates");
                if (cand == null || cand.isEmpty()) continue;
                JsonObject content = cand.get(0).getAsJsonObject().getAsJsonObject("content");
                if (content == null) continue;
                for (JsonElement p : content.getAsJsonArray("parts")) {
                    JsonObject po = p.getAsJsonObject();
                    JsonObject inline = po.has("inlineData") ? po.getAsJsonObject("inlineData")
                            : (po.has("inline_data") ? po.getAsJsonObject("inline_data") : null);
                    if (inline == null) continue;
                    byte[] pcm = Base64.getDecoder().decode(inline.get("data").getAsString());
                    int rate = 24000;
                    String mime = inline.has("mimeType") ? inline.get("mimeType").getAsString() : "";
                    int idx = mime.indexOf("rate=");
                    if (idx >= 0) {
                        try {
                            rate = Integer.parseInt(mime.substring(idx + 5).replaceAll("[^0-9].*$", ""));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    return toMono48k(pcm, rate);
                }
            } catch (GeminiException e) {
                RayaPrimeMod.LOGGER.warn("[RayaPrime] TTS: {}", e.getMessage());
                if (e.status == 401 || e.status == 403 || e.status == 429) return null;
            } catch (Exception e) {
                RayaPrimeMod.LOGGER.warn("[RayaPrime] TTS не удался на модели {}", model, e);
            }
        }
        return null;
    }

    /** PCM s16le моно (rate Гц) → short[] 48 кГц линейной интерполяцией. */
    static short[] toMono48k(byte[] pcm, int rate) {
        int n = pcm.length / 2;
        short[] src = new short[n];
        for (int i = 0; i < n; i++) {
            src[i] = (short) ((pcm[2 * i] & 0xFF) | (pcm[2 * i + 1] << 8));
        }
        if (rate == 48000 || n < 2) return src;
        int outLen = (int) ((long) n * 48000 / rate);
        short[] out = new short[outLen];
        double step = (double) rate / 48000.0;
        for (int i = 0; i < outLen; i++) {
            double pos = i * step;
            int i0 = (int) pos;
            int i1 = Math.min(i0 + 1, n - 1);
            double frac = pos - i0;
            out[i] = (short) (src[i0] * (1.0 - frac) + src[i1] * frac);
        }
        return out;
    }
}
