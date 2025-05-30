package de.kai_morich.simple_bluetooth_le_terminal;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

public class Utility {
    public static Context appContext;
    private static TextToSpeech tts;
    private static boolean isInitialized = false;

    public static void speak(final String text) {
        if (tts == null) {
            tts = new TextToSpeech(appContext.getApplicationContext(), status -> {
                if (status == TextToSpeech.SUCCESS) {
                    int result = tts.setLanguage(Locale.KOREAN);
                    if (result == TextToSpeech.LANG_MISSING_DATA ||
                            result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Log.e("TTSUtil", "한국어 음성 언어를 지원하지 않음");
                    } else {
                        isInitialized = true;
                        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1");
                    }
                } else {
                    Log.e("TTSUtil", "TTS 초기화 실패");
                }
            });
        } else if (isInitialized) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts1");
        }
    }

    public static void shutdown() {
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
            isInitialized = false;
        }
    }
}
