package ru.selfin.backend.dto.pocket;

import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class PocketScopeTest {

    @Test
    void parse_null_isNextIncome() {
        assertThat(PocketScope.parse(null).type()).isEqualTo(PocketScope.Type.NEXT_INCOME);
    }

    @Test
    void parse_explicitNextIncome() {
        assertThat(PocketScope.parse("NEXT_INCOME").type()).isEqualTo(PocketScope.Type.NEXT_INCOME);
    }

    @Test
    void parse_months() {
        PocketScope s = PocketScope.parse("MONTHS:3");
        assertThat(s.type()).isEqualTo(PocketScope.Type.MONTHS);
        assertThat(s.months()).isEqualTo(3);
    }

    @Test
    void parse_monthsOutOfRange_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("MONTHS:0"));
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("MONTHS:37"));
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("MONTHS:abc"));
    }

    @Test
    void parse_date() {
        PocketScope s = PocketScope.parse("DATE:2027-03-01");
        assertThat(s.type()).isEqualTo(PocketScope.Type.DATE);
        assertThat(s.date()).isEqualTo(LocalDate.of(2027, 3, 1));
    }

    @Test
    void parse_garbage_throws() {
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("GARBAGE"));
        assertThatIllegalArgumentException().isThrownBy(() -> PocketScope.parse("DATE:not-a-date"));
    }
}
