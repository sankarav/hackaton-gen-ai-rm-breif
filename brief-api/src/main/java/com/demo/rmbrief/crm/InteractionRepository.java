package com.demo.rmbrief.crm;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface InteractionRepository extends JpaRepository<Interaction, Long> {

    /** Most recent meeting for a client — used to compute delta window. */
    Optional<Interaction> findTopByClientClientIdOrderByMeetingDateDesc(String clientId);
}
