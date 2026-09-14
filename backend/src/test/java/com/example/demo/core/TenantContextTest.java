package com.example.demo.core;

import com.example.demo.exception.MissingTenantException;
import com.example.demo.tenant.TenantContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CORE test - drzac tenant konteksta: postavljanje, citanje, ciscenje i
 * obavezni require() koji ne smije tiho vratiti null.
 */
@Tag("core")
class TenantContextTest {

    private final TenantContext context = new TenantContext();

    @Test
    @DisplayName("require vraca postavljenu firmu")
    void requireReturnsSetCompany() {
        context.set(3L);
        assertThat(context.require()).isEqualTo(3L);
    }

    @Test
    @DisplayName("require baca MissingTenantException kad firma nije postavljena")
    void requireThrowsWhenUnset() {
        assertThatThrownBy(context::require).isInstanceOf(MissingTenantException.class);
    }

    @Test
    @DisplayName("clear uklanja firmu, pa je sljedeci require ponovno greska")
    void clearRemovesCompany() {
        context.set(3L);
        context.clear();

        assertThat(context.get()).isNull();
        assertThatThrownBy(context::require).isInstanceOf(MissingTenantException.class);
    }
}
