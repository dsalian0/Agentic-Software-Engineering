package com.salian.shortener.link;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.salian.shortener.config.ShortenerProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class LinkServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

    @Mock
    private ShortLinkRepository repository;

    @Mock
    private CodeGenerator codeGenerator;

    private LinkService service;

    @BeforeEach
    void setUp() {
        ShortenerProperties properties = new ShortenerProperties("http://sho.rt", 7, 3);
        service = new LinkService(repository, codeGenerator, List.of(new BasicTargetUrlPolicy()), properties,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsLinkWithGeneratedCode() {
        when(codeGenerator.next()).thenReturn("Ab3dE9x");
        when(repository.existsByCode("Ab3dE9x")).thenReturn(false);
        when(repository.saveAndFlush(any(ShortLink.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortLink link = service.create("  https://example.com/page  ", null);

        assertThat(link.getCode()).isEqualTo("Ab3dE9x");
        assertThat(link.getTargetUrl()).isEqualTo("https://example.com/page");
        assertThat(link.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void retriesWithNewCodeWhenGeneratedCodeExists() {
        when(codeGenerator.next()).thenReturn("taken01", "fresh02");
        when(repository.existsByCode("taken01")).thenReturn(true);
        when(repository.existsByCode("fresh02")).thenReturn(false);
        when(repository.saveAndFlush(any(ShortLink.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create("https://example.com", null).getCode()).isEqualTo("fresh02");
    }

    @Test
    void retriesWhenConcurrentInsertViolatesUniqueConstraint() {
        when(codeGenerator.next()).thenReturn("race001", "race002");
        when(repository.existsByCode(any())).thenReturn(false);
        when(repository.saveAndFlush(any(ShortLink.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.create("https://example.com", null).getCode()).isEqualTo("race002");
    }

    @Test
    void givesUpAfterBoundedAttempts() {
        when(codeGenerator.next()).thenReturn("same000");
        when(repository.existsByCode("same000")).thenReturn(true);

        assertThatThrownBy(() -> service.create("https://example.com", null))
                .isInstanceOf(CodeSpaceExhaustedException.class)
                .hasMessageContaining("3 attempts");
        verify(codeGenerator, times(3)).next();
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void createsLinkWithCustomAlias() {
        when(repository.existsByCode("spring-sale")).thenReturn(false);
        when(repository.saveAndFlush(any(ShortLink.class))).thenAnswer(inv -> inv.getArgument(0));

        ShortLink link = service.create("https://example.com", "spring-sale");

        ArgumentCaptor<ShortLink> captor = ArgumentCaptor.forClass(ShortLink.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("spring-sale");
        assertThat(link.getCode()).isEqualTo("spring-sale");
        verify(codeGenerator, never()).next();
    }

    @Test
    void rejectsAliasAlreadyInUse() {
        when(repository.existsByCode("spring-sale")).thenReturn(true);

        assertThatThrownBy(() -> service.create("https://example.com", "spring-sale"))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void mapsConcurrentAliasRaceToConflict() {
        when(repository.existsByCode("spring-sale")).thenReturn(false);
        when(repository.saveAndFlush(any(ShortLink.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.create("https://example.com", "spring-sale"))
                .isInstanceOf(AliasAlreadyExistsException.class);
    }

    @Test
    void rejectsInvalidTargets() {
        assertThatThrownBy(() -> service.create("not a url", null)).isInstanceOf(InvalidTargetUrlException.class);
        assertThatThrownBy(() -> service.create("javascript:alert(1)", null)).isInstanceOf(InvalidTargetUrlException.class);
        assertThatThrownBy(() -> service.create(" ", null)).isInstanceOf(InvalidTargetUrlException.class);
        assertThatThrownBy(() -> service.create("https://example.com/" + "a".repeat(2048), null))
                .isInstanceOf(InvalidTargetUrlException.class);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void resolveThrowsWhenCodeUnknown() {
        when(repository.findByCode("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolve("nope")).isInstanceOf(LinkNotFoundException.class);
    }
}
