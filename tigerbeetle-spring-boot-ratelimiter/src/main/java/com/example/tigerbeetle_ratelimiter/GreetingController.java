package com.example.tigerbeetle_ratelimiter;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class GreetingController {

    @GetMapping("/greeting")
    public String greeting() {
        return "hello";
    }
}
