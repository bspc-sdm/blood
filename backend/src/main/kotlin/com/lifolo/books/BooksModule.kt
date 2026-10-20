package com.lifolo.books

import com.lifolo.common.action.ActionHandler
import com.lifolo.common.action.ActionModule
import org.springframework.stereotype.Component

/** Pharmacy shop books (shopbooks.js): products, purchases, sales, adjustments, daily aggregates, P&L. */
@Component
class BooksModule(private val books: BooksService) : ActionModule {
    override fun actions(): Map<String, ActionHandler> = mapOf(
        "books_items" to ActionHandler { books.items(it) },
        "books_item_save" to ActionHandler { books.itemSave(it) },
        "books_item_delete" to ActionHandler { books.itemDelete(it) },
        "books_item_stock" to ActionHandler { books.itemStock(it) },
        "books_purchase_add" to ActionHandler { books.purchaseAdd(it) },
        "books_purchases" to ActionHandler { books.purchases(it) },
        "books_purchase_delete" to ActionHandler { books.purchaseDelete(it) },
        "books_sale_add" to ActionHandler { books.saleAdd(it) },
        "books_rx_preview" to ActionHandler { books.rxPreview(it) },
        "books_sales" to ActionHandler { books.sales(it) },
        "books_sale_delete" to ActionHandler { books.saleDelete(it) },
        "books_adjust" to ActionHandler { books.adjust(it) },
        "books_adjusts" to ActionHandler { books.adjusts(it) },
        "books_today" to ActionHandler { books.today(it) },
        "books_report" to ActionHandler { books.report(it) },
    )
}
