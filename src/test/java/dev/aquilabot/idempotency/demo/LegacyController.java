package dev.aquilabot.idempotency.demo;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** No annotation. Protected only when idempotency.path-patterns matches it. */
@RestController
@RequestMapping("/legacy")
class LegacyController {

    private final HandlerRuns runs;

    LegacyController(HandlerRuns runs) {
        this.runs = runs;
    }

    @PostMapping("/notes")
    String create(@RequestBody String body) {
        return "legacy " + runs.increment();
    }
}
