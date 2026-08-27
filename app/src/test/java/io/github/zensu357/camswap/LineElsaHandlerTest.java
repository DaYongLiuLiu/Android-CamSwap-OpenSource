package io.github.zensu357.camswap;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LineElsaHandlerTest {

    @Test
    public void isLinePackage_recognizesStandardLinePackage() {
        assertTrue(LineElsaHandler.isLinePackage("jp.naver.line.android"));
    }

    @Test
    public void isLinePackage_recognizesSubProcesses() {
        assertTrue(LineElsaHandler.isLinePackage("jp.naver.line.android:voip"));
        assertTrue(LineElsaHandler.isLinePackage("jp.naver.line.android:chat"));
    }

    @Test
    public void isLinePackage_caseInsensitive() {
        assertTrue(LineElsaHandler.isLinePackage("JP.NAVER.LINE.ANDROID"));
    }

    @Test
    public void isLinePackage_rejectsOtherPackages() {
        assertFalse(LineElsaHandler.isLinePackage("com.whatsapp"));
        assertFalse(LineElsaHandler.isLinePackage("com.tencent.mm"));
        assertFalse(LineElsaHandler.isLinePackage(""));
        assertFalse(LineElsaHandler.isLinePackage(null));
    }
}
