package com.flowcortex.credit.infrastructure;

import com.flowcortex.credit.domain.CreditWallet;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CreditWalletRepository extends JpaRepository<CreditWallet, UUID> {
}
