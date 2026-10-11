package com.coreproof.backend.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @GetMapping("/health")
    public String health(){
        return "Coreproof backend is up";
    }

    @GetMapping("/echo/{ref}")
    public String echo(@PathVariable String ref) {
        return "You sent: " + ref;
    }
}
