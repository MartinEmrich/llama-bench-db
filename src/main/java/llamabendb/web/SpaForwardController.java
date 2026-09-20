package llamabendb.web;

import llamabendb.api.NotFoundException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * Forwards extensionless, non-API paths to the SPA's index.html so that
 * client-side routes survive a browser refresh. Unknown /api paths get a
 * JSON 404 instead.
 */
@Controller
public class SpaForwardController {

    @GetMapping({"/{path:[^\\.]*}", "/{path:[^\\.]*}/{subpath:[^\\.]*}"})
    public String forward(@PathVariable String path) {
        if ("api".equals(path)) {
            throw new NotFoundException("not found");
        }
        return "forward:/index.html";
    }
}
