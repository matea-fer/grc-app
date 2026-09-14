package com.example.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * JWT_SECRET namjerno nema default u application.properties - aplikacija se ne smije
 * podici s ugradenom tajnom. Zato ga test mora dati sam, inace bi contextLoads ovisio
 * o tome je li developer vec dopunio svoj .env.
 */
@SpringBootTest(properties = "app.jwt.secret=tajna-samo-za-testove-dovoljno-duga-32+")
class DemoApplicationTests {

    @Test
    void contextLoads() {
    }

}
