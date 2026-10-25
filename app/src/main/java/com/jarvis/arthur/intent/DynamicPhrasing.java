package com.jarvis.arthur.intent;

import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Generates natural, varied phrasing for Jarvis responses.
 * Designed with a natural, warm, friendly, and clear tone like a personal conversational companion.
 */
public class DynamicPhrasing {
    private static final Random RANDOM = new Random();

    private static final String[] APP_LAUNCH_PHRASES = {
            "Sure thing! Opening %s for you right now.",
            "You got it, opening %s now.",
            "Happy to help. Launching %s for you!",
            "Opening %s right away.",
            "Here is %s for you!"
    };

    private static final String[] FLASHLIGHT_ON_PHRASES = {
            "I've turned on the flashlight for you!",
            "Flashlight is on, all set.",
            "Got it, turning on the flashlight.",
            "Flashlight is switched on!"
    };

    private static final String[] FLASHLIGHT_OFF_PHRASES = {
            "I've turned off the flashlight.",
            "Flashlight is now off.",
            "Got it, turning off the flashlight.",
            "Flashlight switched off for you."
    };

    private static final String[] VOLUME_PHRASES = {
            "I've set the volume to %s for you.",
            "Volume adjusted to %s.",
            "Got it, volume is now at %s.",
            "Audio is set to %s for you."
    };

    private static final String[] BATTERY_PHRASES = {
            "Your battery is currently at %s.",
            "You have %s battery remaining.",
            "Checking your power: you're sitting at %s right now.",
            "Your battery level is at %s."
    };

    private static final String[] MATH_PHRASES = {
            "That comes out to %s.",
            "The answer is %s.",
            "That equals %s.",
            "According to the numbers, that is %s."
    };

    private static final String[] KNOWLEDGE_INTROS = {
            "Here is what I found: ",
            "Regarding %s, ",
            "Here are the details: ",
            "From what I found, ",
            ""
    };

    private static final String[] GREETING_PHRASES_MORNING = {
            "Good morning! Hope you're having a wonderful start to your day. What can I help you with?",
            "Hey, good morning! It's great to hear from you. What are we getting into today?",
            "Morning! Ready to assist whenever you need. How can I help today?"
    };

    private static final String[] GREETING_PHRASES_AFTERNOON = {
            "Good afternoon! How is your day going so far? Let me know what you need.",
            "Hey there, good afternoon! I'm here and ready to help. What's on your mind?",
            "Good afternoon! Everything is running smoothly. How can I assist you today?"
    };

    private static final String[] GREETING_PHRASES_EVENING = {
            "Good evening! Hope you had a great day today. What can I do for you tonight?",
            "Hey, good evening! Relax or get things done, I'm right here with you. What's on your mind?",
            "Good evening! It's good to catch up with you. How can I help?"
    };

    private static final String[] GREETING_PHRASES_GENERAL = {
            "Hey there! It's great to hear from you. What can I help you with today?",
            "Hello! I'm here and happy to help. What's on your mind?",
            "Hey! Always good to talk with you. What can I do for you today?"
    };

    private static final String[] CREATOR_PHRASES = {
            "Arthur created and built me to be a personal, helpful AI companion.",
            "Arthur designed me! He engineered my systems so I can be right here as a friendly companion and assistant.",
            "My creator is Arthur. He built my AI core to assist with questions, apps, and daily tasks.",
            "Arthur made me from the ground up to be your thoughtful, conversational AI partner."
    };

    private static final String[] IDENTITY_PHRASES = {
            "I'm Jarvis—your personal AI companion. Always here to help, answer questions, and keep you company.",
            "My name is Jarvis! Think of me as your friendly, personal assistant ready to help whenever you need.",
            "I'm Jarvis, your conversational companion. Always happy to chat, look up answers, or help you get things done."
    };

    private static final String[] GRATITUDE_RESPONSES = {
            "You're very welcome! Always happy to help.",
            "Anytime! Let me know if you need anything else.",
            "It's my pleasure! I'm always right here for you.",
            "Glad I could help! What's next on your mind?"
    };

    /**
     * Pick random item from array.
     */
    private static String pickRandom(String[] array) {
        return array[RANDOM.nextInt(array.length)];
    }

    public static String getAppLaunchResponse(String appLabel) {
        return String.format(Locale.US, pickRandom(APP_LAUNCH_PHRASES), appLabel);
    }

    public static String getFlashlightResponse(boolean on) {
        return on ? pickRandom(FLASHLIGHT_ON_PHRASES) : pickRandom(FLASHLIGHT_OFF_PHRASES);
    }

    public static String getVolumeResponse(String target) {
        return String.format(Locale.US, pickRandom(VOLUME_PHRASES), target);
    }

    public static String getBatteryResponse(String batteryStatusStr) {
        return String.format(Locale.US, pickRandom(BATTERY_PHRASES), batteryStatusStr);
    }

    public static String getMathResponse(String result) {
        return String.format(Locale.US, pickRandom(MATH_PHRASES), result);
    }

    public static String formatKnowledgeExtract(String topic, String extract) {
        String intro = pickRandom(KNOWLEDGE_INTROS);
        if (intro.contains("%s")) {
            intro = String.format(Locale.US, intro, topic);
        }
        return intro + extract;
    }

    public static String getDynamicGreeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (hour >= 5 && hour < 12) {
            return pickRandom(GREETING_PHRASES_MORNING);
        } else if (hour >= 12 && hour < 18) {
            return pickRandom(GREETING_PHRASES_AFTERNOON);
        } else if (hour >= 18 && hour < 23) {
            return pickRandom(GREETING_PHRASES_EVENING);
        } else {
            return pickRandom(GREETING_PHRASES_GENERAL);
        }
    }

    public static String getDynamicPersonaResponse(String lowerQuery) {
        if (lowerQuery.contains("who created you") || lowerQuery.contains("who made you") ||
                lowerQuery.contains("who built you")) {
            return pickRandom(CREATOR_PHRASES);
        }
        if (lowerQuery.contains("who are you") || lowerQuery.contains("what is your name")) {
            return pickRandom(IDENTITY_PHRASES);
        }
        if (lowerQuery.contains("thank")) {
            return pickRandom(GRATITUDE_RESPONSES);
        }
        return getDynamicGreeting();
    }

    public static String formatWeatherResponse(String areaName, String desc, String tempC,
                                              String feelsLikeC, String humidity, String wind) {
        String[] weatherTemplates = {
                "Right now in %s, it's %s at %s degrees Celsius, feeling like %s degrees with %s percent humidity and winds around %s km/h.",
                "Looking at %s today: it's %s with a temperature of %s degrees Celsius, feels like %s degrees, and humidity at %s percent.",
                "In %s right now, you have %s at %s degrees Celsius with winds reaching %s km/h."
        };
        int choice = RANDOM.nextInt(weatherTemplates.length);
        if (choice == 0) {
            return String.format(Locale.US, weatherTemplates[0], areaName, desc, tempC, feelsLikeC, humidity, wind);
        } else if (choice == 1) {
            return String.format(Locale.US, weatherTemplates[1], areaName, desc, tempC, feelsLikeC, humidity);
        } else {
            return String.format(Locale.US, weatherTemplates[2], areaName, desc, tempC, wind);
        }
    }

    private static final String[] WIFI_ON_PHRASES = {
            "Wi-Fi has been enabled for you, sir.",
            "I've switched on Wi-Fi. Connected and ready.",
            "Wi-Fi is now active, sir.",
            "All set! Wi-Fi connectivity is turned on."
    };

    private static final String[] WIFI_OFF_PHRASES = {
            "Wi-Fi has been turned off, sir.",
            "I've disconnected Wi-Fi as requested.",
            "Wi-Fi is now disabled, sir.",
            "Got it, Wi-Fi is powered down."
    };

    private static final String[] BLUETOOTH_ON_PHRASES = {
            "Bluetooth has been activated for you, sir.",
            "I've turned on Bluetooth. Ready for pairing.",
            "Bluetooth is now enabled, sir.",
            "All set! Bluetooth radio is powered on."
    };

    private static final String[] BLUETOOTH_OFF_PHRASES = {
            "Bluetooth has been switched off, sir.",
            "I've disabled Bluetooth as requested.",
            "Bluetooth is now turned off, sir.",
            "Got it, Bluetooth radio is powered down."
    };

    private static final String[] DND_ON_PHRASES = {
            "Do Not Disturb mode activated, sir. All calls and notifications are silenced.",
            "I've turned on Do Not Disturb. Silent protocol in effect, sir.",
            "Do Not Disturb is now active. Your device will stay quiet.",
            "Quiet mode enabled, sir. Alerts and rings are silenced."
    };

    private static final String[] DND_OFF_PHRASES = {
            "Do Not Disturb disabled, sir. Normal audio alerts restored.",
            "I've deactivated Do Not Disturb. Standard notifications are back on.",
            "Do Not Disturb is turned off, sir. Normal ringer active.",
            "All set! Standard alert tones restored."
    };

    private static final String[] MOBILE_DATA_PHRASES = {
            "Opening cellular data controls for you right now, sir.",
            "Accessing your mobile data settings, sir.",
            "Opening cellular connectivity options on screen, sir.",
            "Here are your mobile data settings, sir."
    };

    public static String getWifiResponse(boolean enable) {
        return pickRandom(enable ? WIFI_ON_PHRASES : WIFI_OFF_PHRASES);
    }

    public static String getBluetoothResponse(boolean enable) {
        return pickRandom(enable ? BLUETOOTH_ON_PHRASES : BLUETOOTH_OFF_PHRASES);
    }

    public static String getDndResponse(boolean enable) {
        return pickRandom(enable ? DND_ON_PHRASES : DND_OFF_PHRASES);
    }

    public static String getMobileDataResponse() {
        return pickRandom(MOBILE_DATA_PHRASES);
    }
}
