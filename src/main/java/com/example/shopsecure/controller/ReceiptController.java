package com.example.shopsecure.controller;

import com.example.shopsecure.dto.response.ReceiptResponse;
import com.example.shopsecure.service.PdfReceiptService;
import com.example.shopsecure.service.ReceiptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/vendor/receipts")
@RequiredArgsConstructor
@Slf4j
public class ReceiptController {

    private final ReceiptService receiptService;
    private final PdfReceiptService pdfReceiptService;

    @GetMapping("/{saleId}")
    public ResponseEntity<ReceiptResponse> generateReceipt(@PathVariable Long saleId) {
        log.info("Generate receipt for sale: saleId={}", saleId);
        return ResponseEntity.ok(receiptService.generateReceipt(saleId));
    }

    @GetMapping("/{saleId}/pdf")
    public ResponseEntity<byte[]> downloadReceiptPdf(@PathVariable Long saleId) {
        log.info("Download receipt PDF for sale: saleId={}", saleId);
        ReceiptResponse receipt = receiptService.generateReceipt(saleId);
        byte[] pdf = pdfReceiptService.generateReceiptPdf(receipt);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=receipt-" + saleId + ".pdf")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
