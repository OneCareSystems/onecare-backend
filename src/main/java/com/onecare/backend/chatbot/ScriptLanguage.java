package com.onecare.backend.chatbot;

/** Tamil or not, by script share (the same 50% rule as Django's CHAT-01). Used only to pick the language of Spring's own messages. */
public final class ScriptLanguage {
    private ScriptLanguage() {}

    public static boolean isTamil(String text) {
        if (text == null) return false;
        int tamil = 0;
        int latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0x0B80 && cp <= 0x0BFF) tamil++;
            else if (Character.isLetter(cp) && cp < 0x250) latin++;
        }
        int total = tamil + latin;
        return total > 0 && (double) tamil / total >= 0.5;
    }
}
