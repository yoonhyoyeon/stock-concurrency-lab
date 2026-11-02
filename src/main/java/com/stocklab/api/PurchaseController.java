package com.stocklab.api;

import com.stocklab.purchase.PurchaseFacade;
import com.stocklab.purchase.PurchaseResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PurchaseController {

    private final PurchaseFacade purchaseFacade;

    public PurchaseController(PurchaseFacade purchaseFacade) {
        this.purchaseFacade = purchaseFacade;
    }

    /** 재고 1개 차감. 200 SUCCESS / 409 SOLD_OUT·CONFLICT / 503 LOCK_TIMEOUT / 500 ERROR */
    @PostMapping("/products/{id}/purchase")
    public ResponseEntity<PurchaseResponse> purchase(@PathVariable("id") long id) {
        PurchaseResult result = purchaseFacade.purchase(id);
        return ResponseEntity.status(result.outcome().httpStatus()).body(PurchaseResponse.from(result));
    }
}
