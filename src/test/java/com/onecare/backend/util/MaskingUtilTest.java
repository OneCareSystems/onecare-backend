package com.onecare.backend.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDS section 2.5.3 partial masking (AC6): deterministic, same output for the
 * on-screen report and its CSV/PDF export.
 */
class MaskingUtilTest {

    @Test
    void maskContactNo_keepsCountryCodeAndLastTwoDigits() {
        assertThat(MaskingUtil.maskContactNo("+94771234567")).isEqualTo("+94*******67");
        assertThat(MaskingUtil.maskContactNo("0771234567")).isEqualTo("0*******67");
    }

    @Test
    void maskContactNo_isDeterministicSoScreenAndExportMatch() {
        String first = MaskingUtil.maskContactNo("0771234567");
        String second = MaskingUtil.maskContactNo("0771234567");
        assertThat(first).isEqualTo(second);
    }

    @Test
    void maskContactNo_neverExposesTheFullNumber() {
        String masked = MaskingUtil.maskContactNo("0719876543");
        assertThat(masked).doesNotContain("9876543");
        assertThat(masked).endsWith("43");
    }

    @Test
    void maskContactNo_passesThroughNullBlankAndShortValues() {
        assertThat(MaskingUtil.maskContactNo(null)).isNull();
        assertThat(MaskingUtil.maskContactNo("   ")).isEqualTo("   ");
        assertThat(MaskingUtil.maskContactNo("123")).isEqualTo("***");
    }

    @Test
    void maskIdNumber_keepsFirstTwoAndLastTwoCharacters() {
        assertThat(MaskingUtil.maskIdNumber("199912345678")).isEqualTo("19********78");
        assertThat(MaskingUtil.maskIdNumber(null)).isNull();
        assertThat(MaskingUtil.maskIdNumber("")).isEqualTo("");
        assertThat(MaskingUtil.maskIdNumber("AB1")).isEqualTo("***");
    }
}
