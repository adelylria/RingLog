package com.adelylria.ringlog.ui.theme;

import java.io.IOException;
import com.adelylria.ringlog.preferences.ApplicationPreferences;

final class ThemePreferences {

    private static final String THEME_KEY = "theme";
    private static final String DARK_THEME = "dark";
    private static final String LIGHT_THEME = "light";
    private static volatile ApplicationPreferences preferences =
            ApplicationPreferences.production();

    private ThemePreferences() {
    }

    static void configure(ApplicationPreferences applicationPreferences) {
        preferences = java.util.Objects.requireNonNull(
                applicationPreferences, "applicationPreferences"
        );
    }

    static boolean loadDarkMode() {
        return DARK_THEME.equalsIgnoreCase(
                preferences.get(THEME_KEY)
        );
    }

    static void saveDarkMode(boolean darkMode) {
        try {
            preferences.put(
                    THEME_KEY, darkMode ? DARK_THEME : LIGHT_THEME
            );
        } catch (IOException exception) {
            com.adelylria.ringlog.diagnostics.SafeLog.failure(
                    "theme_preferences_write", exception
            );
        }
    }
}
