package br.com.estudo.flashsale.infrastructure;

import jakarta.inject.Qualifier;
import java.lang.annotation.*;
@Qualifier @Retention(RetentionPolicy.RUNTIME) @Target({ElementType.TYPE, ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD})
public @interface Transport {}
