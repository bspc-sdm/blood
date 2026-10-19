package com.lifolo.books

/**
 * Pharmacy shop books: products, purchases, sales, adjustments, daily aggregates, P&L.
 *
 * Phase 2 ports these from the Node server (see backend/docs/API-INVENTORY.md and PHASE2-PLAN.md):
 * books_items, books_item_save, books_item_delete, books_item_stock, books_purchase_add, books_purchases, books_purchase_delete, books_sale_add, books_rx_preview, books_sales, books_sale_delete, books_adjust, books_adjusts, books_today, books_report
 *
 * Add an @Component implementing com.lifolo.common.action.ActionModule here; the registry picks it up.
 */
internal object BooksModule
