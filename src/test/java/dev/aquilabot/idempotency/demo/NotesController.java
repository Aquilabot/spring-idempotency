package dev.aquilabot.idempotency.demo;

import dev.aquilabot.idempotency.Idempotent;
import java.net.URI;
import java.util.concurrent.Callable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/notes")
class NotesController {

    record NewNote(String text) {
    }

    record Note(int id, String text) {
    }

    private final HandlerRuns runs;
    private final long delayMillis;

    NotesController(HandlerRuns runs, @Value("${demo.handler-delay-ms:0}") long delayMillis) {
        this.runs = runs;
        this.delayMillis = delayMillis;
    }

    @Idempotent
    @PostMapping
    ResponseEntity<Note> create(@RequestBody NewNote body) throws InterruptedException {
        int run = runs.increment();
        if (delayMillis > 0) {
            Thread.sleep(delayMillis);
        }
        return ResponseEntity.created(URI.create("/notes/" + run))
                .header("X-Handler-Run", String.valueOf(run))
                .body(new Note(run, body.text()));
    }

    @Idempotent
    @PatchMapping("/{id}")
    Note update(@PathVariable int id, @RequestBody NewNote body) {
        runs.increment();
        return new Note(id, body.text());
    }

    @PostMapping("/unprotected")
    Note unprotected(@RequestBody NewNote body) {
        return new Note(runs.increment(), body.text());
    }

    @Idempotent(required = false)
    @PostMapping("/optional")
    Note optional(@RequestBody NewNote body) {
        return new Note(runs.increment(), body.text());
    }

    @Idempotent(ttl = "10m")
    @PostMapping("/short-ttl")
    Note shortTtl(@RequestBody NewNote body) {
        return new Note(runs.increment(), body.text());
    }

    @Idempotent
    @PostMapping("/unavailable")
    ResponseEntity<String> unavailable() {
        runs.increment();
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body("try again");
    }

    @Idempotent
    @PostMapping("/throttled")
    ResponseEntity<String> throttled() {
        runs.increment();
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "1").body("slow down");
    }

    @Idempotent
    @PostMapping("/throws")
    Note throwsException() {
        runs.increment();
        throw new IllegalStateException("handler failed");
    }

    @Idempotent
    @PostMapping("/missing")
    Note missing() {
        runs.increment();
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such list");
    }

    @Idempotent
    @PostMapping("/rejected")
    ResponseEntity<ProblemDetail> rejected() {
        runs.increment();
        return ResponseEntity.badRequest().body(ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "text is required"));
    }

    @Idempotent
    @PostMapping(path = "/form", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    Note form(@RequestParam String text) {
        return new Note(runs.increment(), text);
    }

    @Idempotent
    @PostMapping("/async")
    Callable<String> async() {
        return () -> "never";
    }
}
