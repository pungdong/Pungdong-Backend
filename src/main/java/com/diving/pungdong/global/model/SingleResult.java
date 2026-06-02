package com.diving.pungdong.global.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SingleResult<T> extends CommonResult {
    private T data;
}
