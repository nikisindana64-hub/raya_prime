package com.mamarala.rayaprime.voice;

import com.mamarala.rayaprime.RayaPrimeMod;
import com.mamarala.rayaprime.ai.GeminiClient;
import com.mamarala.rayaprime.config.RayaConfig;
import com.mamarala.rayaprime.entity.RayaPrimeEntity;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;

import java.util.concurrent.CompletableFuture;

/**
 * Фасад озвучки. НЕ импортирует классы Simple Voice Chat, поэтому безопасен, даже если мод voicechat не установлен.
 * Всё, что трогает API голосового чата, лежит в {@link RayaVoiceChatPlugin} и вызывается только при его наличии.
 */
public final class RayaVoice {

    private RayaVoice() {}

    public static boolean isVoiceChatLoaded() {
        try {
            return ModList.get().isLoaded("voicechat");
        } catch (Throwable t) {
            return false;
        }
    }

    /** Озвучить реплику от тела Раи (вызывать из серверного потока). Синтез — в фоне, воспроизведение — на сервере. */
    public static void speak(MinecraftServer server, RayaPrimeEntity raya, String text, String apiKey) {
        if (!RayaConfig.ENABLE_3D_VOICE.get() || text == null || text.isBlank() || !isVoiceChatLoaded()) {
            return;
        }
        final boolean crimson = raya.isCrimsonMode();
        final boolean tts = RayaConfig.USE_GEMINI_TTS.get() && apiKey != null && !apiKey.isEmpty();
        CompletableFuture.runAsync(() -> {
            short[] pcm = null;
            if (tts) {
                pcm = GeminiClient.synthesizeSpeech48k(apiKey, text);
            }
            if (pcm == null || pcm.length == 0) {
                pcm = synthesizeBeeps(text, crimson);
            }
            final short[] audio = pcm;
            server.execute(() -> {
                try {
                    if (raya.isAlive() && !raya.isRemoved()) {
                        RayaVoiceChatPlugin.play(raya, audio);
                    }
                } catch (Throwable t) {
                    RayaPrimeMod.LOGGER.debug("[RayaPrime] Не удалось воспроизвести голос", t);
                }
            });
        });
    }

    /** Запасной «роботизированный» голос: мелодичные тона по слогам (48 кГц, 16 бит, моно). */
    static short[] synthesizeBeeps(String text, boolean crimson) {
        int sampleRate = 48000;
        int syllables = Math.min(24, Math.max(4, text.length() / 4));
        int samplesPerSyllable = sampleRate / 12;
        short[] out = new short[syllables * samplesPerSyllable];
        double baseFreq = crimson ? 260.0 : 520.0;
        for (int s = 0; s < syllables; s++) {
            char c = text.charAt((s * 3) % text.length());
            double freq = baseFreq + ((c % 7) * (crimson ? 28.0 : 55.0));
            for (int i = 0; i < samplesPerSyllable; i++) {
                double t = (double) i / sampleRate;
                double env = Math.sin(Math.PI * i / samplesPerSyllable);
                double wave = Math.sin(2.0 * Math.PI * freq * t) * 0.65
                        + Math.sin(2.0 * Math.PI * (freq * 1.5) * t) * 0.35;
                out[s * samplesPerSyllable + i] = (short) (wave * env * 5500);
            }
        }
        return out;
    }
}
