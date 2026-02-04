package com.isums.contractservice;

import com.isums.contractservice.services.EContractServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.lang.reflect.Method;
import java.util.UUID;

@SpringBootTest
class EEContractServiceApplicationTests {

    @Autowired
    private EContractServiceImpl eContractService;

    @Test
    void contextLoads() {
    }

    @Test
    void testCreateTableInDocumentVN() throws Exception {
        Method method = EContractServiceImpl.class.getDeclaredMethod("createTableInDocumentVN", UUID.class);
        method.setAccessible(true);
        String htmlTable = (String) method.invoke(eContractService, UUID.randomUUID());
        System.out.println("[DEBUG_LOG] Generated Table HTML:");
        System.out.println(htmlTable);
    }

    @Test
    void testRenderHtmlToPdf() throws Exception {
        String testHtml = "<!DOCTYPE html><html lang=\"vi\"><body><h1>Test</h1></body></html>";
        Method method = EContractServiceImpl.class.getDeclaredMethod("renderHtmlToPdf", String.class);
        method.setAccessible(true);
        byte[] pdf = (byte[]) method.invoke(eContractService, testHtml);
        assert pdf != null && pdf.length > 0;
    }

    @Test
    void testRenderHtmlToPdfWithEmptyLineBefore() throws Exception {
        String testHtml = "\n<!DOCTYPE html><html lang=\"vi\"><body><h1>Test</h1></body></html>";
        Method method = EContractServiceImpl.class.getDeclaredMethod("renderHtmlToPdf", String.class);
        method.setAccessible(true);
        byte[] pdf = (byte[]) method.invoke(eContractService, testHtml);
        assert pdf != null && pdf.length > 0;
    }

    @Test
    void testRenderHtmlToPdfWithBOM() throws Exception {
        String testHtml = "\uFEFF<!DOCTYPE html><html lang=\"vi\"><body><h1>Test</h1></body></html>";
        Method method = EContractServiceImpl.class.getDeclaredMethod("renderHtmlToPdf", String.class);
        method.setAccessible(true);
        byte[] pdf = (byte[]) method.invoke(eContractService, testHtml);
        assert pdf != null && pdf.length > 0;
    }

    @Test
    void testRenderHtmlToPdfWithMultipleMalformed() throws Exception {
        String testHtml = "\n\r\n \uFEFF <!DOCTYPE html> \n <html lang=\"vi\"><body><h1>Test</h1></body></html>";
        Method method = EContractServiceImpl.class.getDeclaredMethod("renderHtmlToPdf", String.class);
        method.setAccessible(true);
        byte[] pdf = (byte[]) method.invoke(eContractService, testHtml);
        assert pdf != null && pdf.length > 0;
    }

    @Test
    void testFindAnchors() throws Exception {
        String testHtml = "<!DOCTYPE html><html lang=\"vi\"><body>" +
                "<div><span style=\"color:white;font-size:1px;\">SIGN_A</span></div>" +
                "<div><span style=\"color:white;font-size:1px;\">SIGN_B</span></div>" +
                "</body></html>";

        Method renderMethod = EContractServiceImpl.class.getDeclaredMethod("renderHtmlToPdf", String.class);
        renderMethod.setAccessible(true);
        byte[] pdfBytes = (byte[]) renderMethod.invoke(eContractService, testHtml);

        Method findAnchorsMethod = EContractServiceImpl.class.getDeclaredMethod("findAnchors", byte[].class, java.util.List.class);
        findAnchorsMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> anchors = (java.util.Map<String, Object>) findAnchorsMethod.invoke(eContractService, pdfBytes, java.util.List.of("SIGN_A", "SIGN_B"));

        assert anchors != null;
        assert anchors.containsKey("SIGN_A");
        assert anchors.containsKey("SIGN_B");
        System.out.println("[DEBUG_LOG] Anchors found: " + anchors.keySet());
    }
}
