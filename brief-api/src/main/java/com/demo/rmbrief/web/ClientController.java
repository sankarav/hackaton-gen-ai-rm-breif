package com.demo.rmbrief.web;

import com.demo.rmbrief.crm.Client;
import com.demo.rmbrief.crm.ClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class ClientController {

    private final ClientRepository clients;

    public ClientController(ClientRepository clients) {
        this.clients = clients;
    }

    @GetMapping("/clients")
    public List<ClientSummary> listClients() {
        return clients.findAll().stream()
                .map(c -> new ClientSummary(c.getClientId(), c.getFullName(), c.getSegment()))
                .toList();
    }

    public record ClientSummary(String clientId, String fullName, String segment) {}
}
