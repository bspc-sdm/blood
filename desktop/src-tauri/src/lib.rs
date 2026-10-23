//! Lifolo ডেস্কটপ (Windows 10/11): পুরো web-vue ওয়েব, সাথে অফলাইন রসিদ (web-vue/src/offline)।
//!
//! Rust অংশে শুধু যা ওয়েব পারে না:
//!  • জানালা: অ্যাপের নিজের ঠিকানা ছাড়া কোথাও যায় না — বাইরের লিংক Windows-এর ব্রাউজারে (নিরাপত্তা পর্যালোচনা I13)
//!  • ক্যামেরা/মাইক/নোটিফিকেশন: শুধু অ্যাপের নিজের পাতাকে সরাসরি অনুমতি (LiveKit কল)
//!  • ট্রে, বন্ধ করলে ট্রেতে লুকানো, একটাই চালু কপি
//!  • থার্মাল প্রিন্টারে কাঁচা বাইট (printer.rs), DPAPI-তে গোপন জিনিস (vault.rs)
mod printer;
mod tray;
mod vault;

use tauri::webview::{NewWindowResponse, PermissionKind, PermissionResponse};
use tauri::{Emitter, Manager, Url, WebviewWindowBuilder, WindowEvent};

pub const MAIN: &str = "main";

/// অ্যাপের নিজের পাতা (বান্ডেল করা web-vue) — https://tauri.localhost (Windows, useHttpsScheme), tauri://localhost (অন্যত্র)
pub fn is_app_url(url: &Url) -> bool {
    match url.scheme() {
        "tauri" => url.host_str() == Some("localhost"),
        "http" | "https" => url.host_str() == Some("tauri.localhost") || (cfg!(debug_assertions) && url.host_str() == Some("localhost")),
        "about" => url.as_str() == "about:blank",
        _ => false,
    }
}

/// বাইরের লিংক: শুধু http(s), mailto, tel — Windows নিজের অ্যাপে খোলে
fn open_external(url: &Url) {
    if matches!(url.scheme(), "http" | "https" | "mailto" | "tel") {
        let _ = tauri_plugin_opener::open_url(url.as_str(), None::<&str>);
    }
}

pub fn show_main<R: tauri::Runtime>(app: &tauri::AppHandle<R>) {
    if let Some(w) = app.get_webview_window(MAIN) {
        let _ = w.unminimize();
        let _ = w.show();
        let _ = w.set_focus();
    }
}

#[derive(serde::Serialize)]
struct AppInfo {
    version: String,
    hidden_start: bool,
}

#[tauri::command]
fn app_info(app: tauri::AppHandle) -> AppInfo {
    AppInfo {
        version: app.package_info().version.to_string(),
        hidden_start: std::env::args().any(|a| a == "--hidden"),
    }
}

/// কম্পিউটারের নাম (desktop_register-এর name)
#[tauri::command]
fn device_name() -> String {
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_else(|_| "Windows PC".into())
        .chars()
        .take(60)
        .collect()
}

/// ট্রে থেকে "বন্ধ করুন" ছাড়াও ওয়েব থেকে (লগআউটের পর মোছা ইত্যাদি)
#[tauri::command]
fn quit_app(app: tauri::AppHandle) {
    app.exit(0);
}

fn create_main(app: &tauri::App) -> tauri::Result<()> {
    let cfg = app
        .config()
        .app
        .windows
        .iter()
        .find(|w| w.label == MAIN)
        .cloned()
        .expect("main window config");
    let hidden = std::env::args().any(|a| a == "--hidden");
    WebviewWindowBuilder::from_config(app.handle(), &cfg)?
        .visible(!hidden)
        .on_navigation(|url| {
            if is_app_url(url) {
                true
            } else {
                open_external(url);
                false
            }
        })
        .on_new_window(|url, _features| {
            open_external(&url);
            NewWindowResponse::Deny
        })
        .on_permission_request(|webview, kind| {
            let own = webview.url().map(|u| is_app_url(&u)).unwrap_or(false);
            match kind {
                PermissionKind::Camera | PermissionKind::Microphone | PermissionKind::Notifications if own => PermissionResponse::Allow,
                PermissionKind::Geolocation if own => PermissionResponse::Default,
                _ => PermissionResponse::Deny,
            }
        })
        .build()?;
    Ok(())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        // প্রথমে: দ্বিতীয়বার খুললে আগের জানালাটাই সামনে
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .plugin(tauri_plugin_sql::Builder::default().build())
        .plugin(tauri_plugin_http::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--hidden"]),
        ))
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![
            app_info,
            device_name,
            quit_app,
            printer::printers_list,
            printer::print_raw,
            vault::secret_get,
            vault::secret_set,
            vault::secret_del,
            vault::secrets_wipe,
            vault::offline_db_drop,
        ])
        .setup(|app| {
            create_main(app)?;
            tray::setup(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            // বন্ধের বোতাম: ট্রেতে লুকানো (কল আর অর্ডারের খবর চালু থাকে); পুরো বন্ধ ট্রের মেনু থেকে
            if let WindowEvent::CloseRequested { api, .. } = event {
                if window.label() == MAIN {
                    api.prevent_close();
                    let _ = window.hide();
                    let _ = window.emit("desktop://hidden", ());
                }
            }
        })
        .run(tauri::generate_context!())
        .expect("error while running Lifolo");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn app_urls() {
        let ok = ["https://tauri.localhost/", "http://tauri.localhost/services", "tauri://localhost/x", "about:blank"];
        for u in ok {
            assert!(is_app_url(&Url::parse(u).unwrap()), "{u}");
        }
        let bad = [
            "https://lifolo.com/",
            "https://tauri.localhost.evil.com/",
            "https://evil.com/?h=tauri.localhost",
            "file:///C:/Windows/",
            "javascript:alert(1)",
            "about:srcdoc",
        ];
        for u in bad {
            assert!(!is_app_url(&Url::parse(u).unwrap()), "{u}");
        }
    }
}
