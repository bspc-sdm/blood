//! ট্রের আইকন: চাপলে Lifolo সামনে; মেনু — খুলুন, ডেস্কটপ সেটিংস, আপডেট দেখুন, বন্ধ করুন।
//! সেটিংস/আপডেট ওয়েবকে "desktop://menu" ঘটনা দিয়ে জানায় (ওয়েব নিজের পাতায় যায়)।
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, Manager};

pub fn setup(app: &tauri::App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Lifolo খুলুন", true, None::<&str>)?;
    let settings = MenuItem::with_id(app, "settings", "ডেস্কটপ সেটিংস", true, None::<&str>)?;
    let update = MenuItem::with_id(app, "update", "আপডেট দেখুন", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "বন্ধ করুন", true, None::<&str>)?;
    let sep = PredefinedMenuItem::separator(app)?;
    let menu = Menu::with_items(app, &[&open, &settings, &update, &sep, &quit])?;

    let mut b = TrayIconBuilder::with_id("lifolo")
        .tooltip("Lifolo")
        .menu(&menu)
        .show_menu_on_left_click(false)
        .on_menu_event(|app, ev| match ev.id().as_ref() {
            "open" => crate::show_main(app),
            "settings" | "update" => {
                crate::show_main(app);
                if let Some(w) = app.get_webview_window(crate::MAIN) {
                    let _ = w.emit("desktop://menu", ev.id().as_ref());
                }
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .on_tray_icon_event(|tray, ev| {
            if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = ev {
                crate::show_main(tray.app_handle());
            }
        });
    if let Some(icon) = app.default_window_icon() {
        b = b.icon(icon.clone());
    }
    b.build(app)?;
    Ok(())
}
