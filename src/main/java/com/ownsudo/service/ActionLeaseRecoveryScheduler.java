package com.ownsudo.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ActionLeaseRecoveryScheduler {
    private static final Logger log = LoggerFactory.getLogger(ActionLeaseRecoveryScheduler.class);
    private final ActionExecutionStateService state;

    public ActionLeaseRecoveryScheduler(ActionExecutionStateService state) {
        this.state = state;
    }

    @Scheduled(fixedDelayString = "${ownagent.runtime.recovery-interval-ms:30000}")
    public void recoverExpiredLeases() {
        int recovered = state.recoverExpiredLeases();
        if (recovered > 0) {
            log.warn("Marked {} expired Action execution lease(s) as UNKNOWN; no automatic retry was attempted", recovered);
        }
    }
}
