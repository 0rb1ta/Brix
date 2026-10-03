package app.brix.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DonationServiceTest {

    @Test
    fun `сервис узнаётся по домену виджета, включая поддомены`() {
        assertEquals(
            DonationService.DONATION_ALERTS,
            DonationService.forUrl("https://www.donationalerts.com/widget/alerts?token=abc"),
        )
        assertEquals(
            DonationService.DONATE_PAY,
            DonationService.forUrl("https://widget.donatepay.ru/alert-box/widget/TOKEN"),
        )
        assertEquals(DonationService.IHAQ, DonationService.forUrl("https://ihaqdonate.com/widget/xxx"))
        assertEquals(DonationService.DONATTY, DonationService.forUrl("https://widget.donatty.com/abc"))
    }

    @Test
    fun `настоящие адреса виджетов владельца, токены вырезаны`() {
        assertEquals(
            DonationService.IHAQ,
            DonationService.forUrl("https://ihaqdonate.com/widget/donation/1/00000000-0000-0000-0000-000000000000"),
        )
        assertEquals(
            DonationService.DONATE_PAY,
            DonationService.forUrl("https://widget.donatepay.ru/alert-box/widget/0000000000000000"),
        )
    }

    @Test
    fun `чужой или кривой адрес — «другой», а не падение`() {
        assertEquals(DonationService.UNKNOWN, DonationService.forUrl("https://example.com/widget"))
        assertEquals(DonationService.UNKNOWN, DonationService.forUrl("не ссылка"))
        assertEquals(DonationService.UNKNOWN, DonationService.forUrl(""))
    }
}
