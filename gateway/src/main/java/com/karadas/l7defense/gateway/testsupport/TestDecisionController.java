package com.karadas.l7defense.gateway.testsupport;

import com.karadas.l7defense.gateway.cache.CachedDecision;
import com.karadas.l7defense.gateway.cache.Decision;
import com.karadas.l7defense.gateway.cache.DecisionCache;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

// TEST-ONLY: manually seeds a decision into the cache, standing in for the Risk
// Engine (which doesn't exist yet). Not routed through Gateway's filter chain --
// no JWT, no rate limit -- remove before this project goes anywhere near production.
@RestController
public class TestDecisionController {

    private final DecisionCache decisionCache;

    public TestDecisionController(DecisionCache decisionCache) {
        this.decisionCache = decisionCache;
    }

    @PostMapping("/test/decisions")
    public String setDecision(@RequestParam String identity,
                              @RequestParam Decision decision,
                              @RequestParam(defaultValue = "60") long seconds) {
        Instant validUntil = Instant.now().plusSeconds(seconds);
        decisionCache.put(identity, new CachedDecision(decision, validUntil));
        return "Set " + identity + " -> " + decision + " until " + validUntil;
    }
}