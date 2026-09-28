# Org demand

`GET /exchange/v1/me/org-demand`, scope `exchange.demand.read`: what the member's units still need,
so a client can show the member where their stock or their crafting helps. It is anonymous and
read-only; there is no paging and no cursor.

## Whose demand

The open and in-progress job orders that a unit **the member is a member of** is responsible for.
Units the member only oversees or administers do not count.

Only a member who may see their organisation's job orders in the Basetool gets the demand: a
member of at least one unit that takes part in the profit sharing, or of a leadership seat above
one. Everyone else, a member of no unit included, gets `200` with two empty lists and `"reason": "NOT_PERMITTED"`:

```json
{
  "updatedAt": "2026-09-28T12:00:00Z",
  "materials": [],
  "items": [],
  "reason": "NOT_PERMITTED"
}
```

The check runs on every request, so a member whose unit leaves the profit sharing stops seeing its
demand at once, even while its orders stay open. An administrator's role does not open it: the
exchange acts with the member's own reach only. A permitted member whose units have nothing open
gets two empty lists **without** a `reason`. Show the member why the view is empty when `reason` is
present, drop any cached demand, and treat a `reason` value you do not know as withheld.

## The answer

An [`org-demand`](../schemas/) document:

```json
{
  "updatedAt": "2026-09-27T12:00:00Z",
  "materials": [
    {
      "material": {"bt": "m-agr", "name": "Agricium"},
      "rawRefs": [{"bt": "m-agr-ore", "name": "Agricium (Ore)"}],
      "minQuality": 650,
      "openQuantity": {"amount": 40, "unit": "SCU"},
      "source": "material-order"
    }
  ],
  "items": [
    {
      "item": {"bt": "i-panther", "name": "CF-337 Panther Repeater"},
      "openQuantity": {"amount": 2, "unit": "PIECE"},
      "craftableByMe": true
    }
  ]
}
```

**`materials[]`** — one line per material, quality floor and source:

| Field | Meaning |
| --- | --- |
| `material` | The material; `bt` is its Basetool id, as in a stock lot. |
| `rawRefs` | Raw ores that refine into this material, at most 20, by name; possibly empty. |
| `minQuality` | The quality floor the orders ask for: 650 for a good-quality requirement, otherwise 0. |
| `openQuantity` | What is still missing: the orders' requirement minus the stock already booked to them at or above that floor, summed over the orders. |
| `source` | `material-order` for a material order, `item-order` for the materials an item order resolves to. |

**`items[]`** — one line per game item that item orders still need:

| Field | Meaning |
| --- | --- |
| `item` | The game item; `bt` is its Basetool id. |
| `openQuantity` | Pieces ordered, minus delivered, minus already reserved for the order. |
| `craftableByMe` | The member holds a blueprint that produces it; variants count when the order counts them. |

`reason` is absent whenever the demand is shown; see [whose demand](#whose-demand).

An item order appears twice: as its item line and as the `item-order` material lines of what it is
made of. Count one or the other, never both. Lines with nothing open are left out. `updatedAt` is
when the server computed the answer.

## What it does not reveal

No requester, assignee, order number or title, unit name, free text or per-order figure — only
catalogue names and summed amounts. Sums are not suppressed for small counts, so a line may stand for
a single order. Treat the document as the member's organisation's internal data.

## Caching

The server computes the demand for each request. A client may keep a copy for **at most 7 days**,
outside cloud-synced folders, never in a backup or a diagnostics bundle, and never forwards it
([client security requirements](../client-security.md)). The demand changes slowly: refresh it
once when the member opens the view, and after that at most every 5 minutes, like any timed sync
([sync cadence](../sync-guide.md#back-off-and-sync-cadence)).
