package com.demo.rmbrief.crm;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface SyntheticProductRepository extends JpaRepository<SyntheticProduct, Long> {

    List<SyntheticProduct> findByClientClientId(String clientId);
}
