"""Demo order-payment service (SEEDED for triage demo).

Contains one intentional, realistic bug that emits a distinctive log line the
Triage Agent must correlate back to file:line. See ../README.md for the answer key.
The numbers below are executed by the __main__ block, so the log line the agent
finds in the Sumo fixture is exactly what this code produces.
"""
import logging

logger = logging.getLogger("payment_service")


def compute_expected_total(order):
    """Expected charge = subtotal - discount + tax, discount applied BEFORE tax.

    BUG (seeded): the gateway (apply_discount) applies the discount AFTER tax.
    A percentage discount on a taxed order therefore yields a different
    `expected` here than what the gateway actually charges, tripping the
    reconcile check below.
    """
    subtotal = order["subtotal"]
    tax = round(subtotal * order["tax_rate"], 2)
    discount = round(subtotal * order["discount_pct"], 2)
    return round(subtotal - discount + tax, 2)


def apply_discount(order, tax_inclusive):
    # Discount applied to the tax-INCLUSIVE amount (order of operations differs
    # from compute_expected_total, which discounts pre-tax) — the root-cause divergence.
    return round(tax_inclusive * (1 - order["discount_pct"]), 2)


class MockGateway:
    """Stand-in payment gateway. Charges the tax-inclusive amount, then discounts it."""
    def charge(self, order):
        tax_inclusive = round(order["subtotal"] * (1 + order["tax_rate"]), 2)
        return apply_discount(order, tax_inclusive)


def reconcile(order, charged):
    expected = compute_expected_total(order)
    if abs(expected - charged) > 0.01:
        logger.error(  # <-- statement starts line 43; distinctive token on line 44
            "PAYMENT_RECONCILE_MISMATCH order=%s expected=%.2f charged=%.2f",
            order["id"], expected, charged,
        )
        raise ValueError("reconcile mismatch")
    logger.info("PAYMENT_OK order=%s amount=%s", order["id"], charged)
    return True


def charge(order, gateway):
    charged = gateway.charge(order)               # gateway applies apply_discount()
    return reconcile(order, charged)


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    demo_order = {"id": "INC-ORD-4471", "subtotal": 10.00, "tax_rate": 0.25, "discount_pct": 0.10}
    # expected = 10 - 1.00 + 2.50 = 11.50 ; charged = 12.50 * 0.90 = 11.25 ; delta 0.25 -> mismatch
    charge(demo_order, MockGateway())
