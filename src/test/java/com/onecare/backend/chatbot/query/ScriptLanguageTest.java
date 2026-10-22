package com.onecare.backend.chatbot.query;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;


class ScriptLanguageTest {
    @Test void tamil_text_is_tamil() { assertTrue(ScriptLanguage.isTamil("கிளினிக் நேரம் என்ன")); }

    @Test void english_and_romanised_tamil_are_not_tamil() {
        assertFalse(ScriptLanguage.isTamil("what are the clinic hours"));
        assertFalse(ScriptLanguage.isTamil("clinic neram enna"));
    }

    @Test void a_tamil_question_with_an_english_drug_name_is_tamil() {
        assertTrue(ScriptLanguage.isTamil("பாராசிட்டமால் paracetamol எதற்கு பயன்படுகிறது"));
    }

    @Test void empty_null_and_digits_are_not_tamil() {
        assertFalse(ScriptLanguage.isTamil(null));
        assertFalse(ScriptLanguage.isTamil(""));
        assertFalse(ScriptLanguage.isTamil("12345 ???"));
    }
}
