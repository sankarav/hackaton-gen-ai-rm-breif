package com.demo.rmbrief.web;

import com.demo.rmbrief.capture.CaptureDtos;
import com.demo.rmbrief.capture.CaptureService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Recording policy (compliance-owned) and tracked client promises. */
@RestController
public class PolicyController {

    private final CaptureService captures;

    public PolicyController(CaptureService captures) {
        this.captures = captures;
    }

    @GetMapping("/policy/recording")
    public CaptureDtos.Policy recordingPolicy() {
        return captures.policy();
    }

    // Unauthenticated in this demo; in production this must require a compliance-admin role.
    @PutMapping("/admin/recording-policy")
    public CaptureDtos.Policy setRecordingPolicy(@RequestBody CaptureDtos.PolicyUpdate body) {
        return captures.setPolicy(body.recordingEnabled(), "compliance-admin");
    }

    @GetMapping("/clients/{clientId}/promises")
    public List<CaptureDtos.Promise> openPromises(@PathVariable String clientId) {
        return captures.openPromises(clientId);
    }

    @PostMapping("/promises/{id}/status")
    public ResponseEntity<Void> setPromiseStatus(@PathVariable long id, @RequestBody CaptureDtos.PromiseStatusUpdate body) {
        captures.setPromiseStatus(id, body.status());
        return ResponseEntity.noContent().build();
    }
}
