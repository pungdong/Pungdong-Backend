package com.diving.pungdong.controller;

import com.diving.pungdong.global.advice.exception.ForbiddenTokenException;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RequiredArgsConstructor
@RestController
@RequestMapping(value = "/exception")
public class ExceptionController {

    @GetMapping(value = "/forbiddenToken")
    public void forbiddenTokenException() {
        throw new ForbiddenTokenException();
    }
}
