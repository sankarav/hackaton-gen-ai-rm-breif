package com.demo.rmbrief.capture;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ClientPromiseRepository extends JpaRepository<ClientPromise, Long> {
    List<ClientPromise> findByClientIdAndStatusOrderByDueDateAsc(String clientId, ClientPromise.Status status);
}
