package com.khwu.meetup.controllers;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

@Controller
public class RouteController {
    @RequestMapping(value = {"/"})
    public String index() {
        return "index.html";
    }
}
