//! গোপন জিনিস (ডেটাবেসের চাবি, কম্পিউটারের device secret, পিনের hash, ৭ দিনের হিসাব) — Windows DPAPI-তে
//! (CryptProtectData, এই Windows ব্যবহারকারীর জন্য)। ফাইল: %LOCALAPPDATA%\com.lifolo.desktop\secrets\<নাম>.bin
//! ডিস্ক খুলে বা অন্য Windows অ্যাকাউন্ট থেকে কপি করলে খোলা যায় না (নিরাপত্তা পর্যালোচনা I4)।
//! নাম শুধু [a-z0-9-], ৮০ অক্ষর পর্যন্ত — পথ বানানো যায় না।
use std::fs;
use std::path::PathBuf;
use tauri::Manager;

#[cfg_attr(not(windows), allow(dead_code))]
const ENTROPY: &[u8] = b"lifolo-desktop-vault-v1";

fn valid_name(name: &str) -> bool {
    !name.is_empty() && name.len() <= 80 && name.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-')
}

fn dir(app: &tauri::AppHandle) -> Result<PathBuf, String> {
    let d = app.path().app_local_data_dir().map_err(|e| e.to_string())?.join("secrets");
    fs::create_dir_all(&d).map_err(|e| e.to_string())?;
    Ok(d)
}

fn file(app: &tauri::AppHandle, name: &str) -> Result<PathBuf, String> {
    if !valid_name(name) {
        return Err("bad secret name".into());
    }
    Ok(dir(app)?.join(format!("{name}.bin")))
}

#[tauri::command]
pub fn secret_get(app: tauri::AppHandle, name: String) -> Result<Option<String>, String> {
    let p = file(&app, &name)?;
    let Ok(raw) = fs::read(&p) else { return Ok(None) };
    match unprotect(&raw) {
        Ok(v) => Ok(Some(String::from_utf8(v).map_err(|e| e.to_string())?)),
        // অন্য ব্যবহারকারী/কম্পিউটারের ফাইল বা নষ্ট: নেই ধরে নেওয়া
        Err(_) => Ok(None),
    }
}

#[tauri::command]
pub fn secret_set(app: tauri::AppHandle, name: String, value: String) -> Result<(), String> {
    if value.len() > 1 << 20 {
        return Err("too large".into());
    }
    let p = file(&app, &name)?;
    let enc = protect(value.as_bytes())?;
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, enc).map_err(|e| e.to_string())?;
    fs::rename(&tmp, &p).map_err(|e| e.to_string())
}

#[tauri::command]
pub fn secret_del(app: tauri::AppHandle, name: String) -> Result<(), String> {
    let p = file(&app, &name)?;
    match fs::remove_file(p) {
        Ok(()) => Ok(()),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(()),
        Err(e) => Err(e.to_string()),
    }
}

/// সব গোপন জিনিস আর সব অফলাইন ডেটাবেস মোছা (১০ বার ভুল পিন / কম্পিউটার বাতিল)
#[tauri::command]
pub fn secrets_wipe(app: tauri::AppHandle) -> Result<(), String> {
    let d = dir(&app)?;
    if let Ok(list) = fs::read_dir(&d) {
        for e in list.flatten() {
            let _ = fs::remove_file(e.path());
        }
    }
    if let Ok(cfg) = app.path().app_config_dir() {
        if let Ok(list) = fs::read_dir(&cfg) {
            for e in list.flatten() {
                let n = e.file_name().to_string_lossy().to_string();
                if n.starts_with("offline-") && (n.ends_with(".db") || n.ends_with(".db-wal") || n.ends_with(".db-shm") || n.ends_with(".db-journal")) {
                    let _ = fs::remove_file(e.path());
                }
            }
        }
    }
    Ok(())
}

/// একটা অফলাইন ডেটাবেস ফাইল মোছা (tauri-plugin-sql রাখে app_config_dir-এ) — নাম offline-<১৬ hex>
#[tauri::command]
pub fn offline_db_drop(app: tauri::AppHandle, name: String) -> Result<(), String> {
    let ok = name.len() == 24 && name.starts_with("offline-") && name[8..].bytes().all(|b| b.is_ascii_hexdigit());
    if !ok {
        return Err("bad db name".into());
    }
    let cfg = app.path().app_config_dir().map_err(|e| e.to_string())?;
    for ext in ["db", "db-wal", "db-shm", "db-journal"] {
        let _ = fs::remove_file(cfg.join(format!("{name}.{ext}")));
    }
    Ok(())
}

#[cfg(windows)]
fn protect(data: &[u8]) -> Result<Vec<u8>, String> {
    dpapi::run(data, true)
}

#[cfg(windows)]
fn unprotect(data: &[u8]) -> Result<Vec<u8>, String> {
    dpapi::run(data, false)
}

#[cfg(windows)]
mod dpapi {
    use windows::Win32::Foundation::{HLOCAL, LocalFree};
    use windows::Win32::Security::Cryptography::{
        CryptProtectData, CryptUnprotectData, CRYPTPROTECT_UI_FORBIDDEN, CRYPT_INTEGER_BLOB,
    };

    pub fn run(data: &[u8], encrypt: bool) -> Result<Vec<u8>, String> {
        let input = CRYPT_INTEGER_BLOB { cbData: data.len() as u32, pbData: data.as_ptr() as *mut u8 };
        let entropy = CRYPT_INTEGER_BLOB { cbData: super::ENTROPY.len() as u32, pbData: super::ENTROPY.as_ptr() as *mut u8 };
        let mut out = CRYPT_INTEGER_BLOB::default();
        unsafe {
            if encrypt {
                CryptProtectData(&input, windows::core::PCWSTR::null(), Some(&entropy as *const _), None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut out)
            } else {
                CryptUnprotectData(&input, None, Some(&entropy as *const _), None, None, CRYPTPROTECT_UI_FORBIDDEN, &mut out)
            }
            .map_err(|e| e.to_string())?;
            let v = std::slice::from_raw_parts(out.pbData, out.cbData as usize).to_vec();
            let _ = LocalFree(Some(HLOCAL(out.pbData as *mut core::ffi::c_void)));
            Ok(v)
        }
    }
}

// Windows ছাড়া (শুধু ডেভেলপমেন্ট/CI-র Linux চেক): সুরক্ষা নেই, তাই চিহ্ন দিয়ে রাখা
#[cfg(not(windows))]
fn protect(data: &[u8]) -> Result<Vec<u8>, String> {
    let mut v = b"PLAIN:".to_vec();
    v.extend_from_slice(data);
    Ok(v)
}

#[cfg(not(windows))]
fn unprotect(data: &[u8]) -> Result<Vec<u8>, String> {
    data.strip_prefix(b"PLAIN:").map(|d| d.to_vec()).ok_or_else(|| "bad".into())
}

#[cfg(test)]
mod tests {
    #[test]
    fn names() {
        assert!(super::valid_name("dbkey"));
        assert!(super::valid_name("dev-offline-0123456789abcdef"));
        for bad in ["", "../x", "A", "a/b", "a.b", "a b", &"x".repeat(81)] {
            assert!(!super::valid_name(bad), "{bad}");
        }
    }

    #[test]
    fn roundtrip() {
        let e = super::protect(b"hello").unwrap();
        assert_ne!(e, b"hello");
        assert_eq!(super::unprotect(&e).unwrap(), b"hello");
    }
}
