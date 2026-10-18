package com.flowcortex.workflow.application;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.credit.api.InsufficientCreditsException;
import com.flowcortex.workflow.api.RunStartGuard;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Refuses a start the member cannot pay for: a balance of zero or less always, a balance below the
 * estimate unless the member acknowledged the cost. Startup fails when a step type the engine can
 * run has no configured cost, whatever the credits switch says.
 */
@Component
@Order(2)
class CreditRunStartGuard implements RunStartGuard {

    private final CreditAccounts creditAccounts;

    CreditRunStartGuard(CreditAccounts creditAccounts, StepExecutorRegistry registry) {
        Set<String> runnable = new TreeSet<>(registry.knownTypes());
        runnable.add(KnownStepTypes.APPROVAL_CHECKPOINT);
        creditAccounts.requirePriced(runnable);
        this.creditAccounts = creditAccounts;
    }

    @Override
    public void check(UUID ownerId, UUID workflowId, List<StepDef> steps, StartContext context) {
        if (!creditAccounts.enabled()) {
            return;
        }
        long required = creditAccounts.estimate(steps.stream().map(StepDef::type).toList());
        long balance = creditAccounts.balance(ownerId);
        if (balance <= 0 || (balance < required && !context.acknowledgeCost())) {
            throw new InsufficientCreditsException(balance, required);
        }
    }
}
