//! থার্মাল রসিদ: Windows-এর প্রিন্টারে কাঁচা (RAW) ESC/POS বাইট পাঠানো (winspool: OpenPrinter → StartDocPrinter "RAW" → WritePrinter)।
//! বাইট বানায় web-vue/src/offline/print (বাংলা লেখা ছবি হয়ে)। প্রিন্টারের নাম হেডার `x-printer`-এ (URL-encoded), বাইট শরীরে।
use serde::Serialize;

#[derive(Serialize, Clone, Debug)]
pub struct PrinterInfo {
    pub name: String,
    pub default: bool,
}

/// সর্বোচ্চ ৮ MB এক রসিদে (৫৭৬ বিন্দু চওড়া, অনেক লম্বা রসিদেও যথেষ্ট)
const MAX_JOB: usize = 8 << 20;

#[tauri::command]
pub async fn printers_list() -> Result<Vec<PrinterInfo>, String> {
    tauri::async_runtime::spawn_blocking(imp::list).await.map_err(|e| e.to_string())?
}

#[tauri::command]
pub async fn print_raw(request: tauri::ipc::Request<'_>) -> Result<(), String> {
    let tauri::ipc::InvokeBody::Raw(bytes) = request.body() else {
        return Err("expected raw bytes".into());
    };
    if bytes.is_empty() || bytes.len() > MAX_JOB {
        return Err("bad job size".into());
    }
    let name = request
        .headers()
        .get("x-printer")
        .and_then(|v| v.to_str().ok())
        .map(percent_decode)
        .ok_or("no printer")?;
    if name.is_empty() || name.len() > 300 || name.contains('\0') {
        return Err("bad printer name".into());
    }
    let data = bytes.clone();
    tauri::async_runtime::spawn_blocking(move || imp::raw(&name, &data)).await.map_err(|e| e.to_string())?
}

fn percent_decode(s: &str) -> String {
    let hex = |c: u8| (c as char).to_digit(16).map(|v| v as u8);
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        if b[i] == b'%' && i + 2 < b.len() {
            if let (Some(h), Some(l)) = (hex(b[i + 1]), hex(b[i + 2])) {
                out.push(h << 4 | l);
                i += 3;
                continue;
            }
        }
        out.push(b[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

#[cfg(windows)]
mod imp {
    use super::PrinterInfo;
    use windows::core::{PCWSTR, PWSTR};
    use windows::Win32::Graphics::Printing::*;

    fn wide(s: &str) -> Vec<u16> {
        s.encode_utf16().chain(std::iter::once(0)).collect()
    }

    unsafe fn from_pwstr(p: PWSTR) -> String {
        if p.is_null() {
            String::new()
        } else {
            p.to_string().unwrap_or_default()
        }
    }

    fn default_name() -> String {
        unsafe {
            let mut n = 0u32;
            let _ = GetDefaultPrinterW(None, &mut n);
            if n == 0 {
                return String::new();
            }
            let mut buf = vec![0u16; n as usize];
            if GetDefaultPrinterW(Some(PWSTR(buf.as_mut_ptr())), &mut n).as_bool() {
                String::from_utf16_lossy(&buf[..buf.iter().position(|&c| c == 0).unwrap_or(buf.len())])
            } else {
                String::new()
            }
        }
    }

    pub fn list() -> Result<Vec<PrinterInfo>, String> {
        let flags = PRINTER_ENUM_LOCAL | PRINTER_ENUM_CONNECTIONS;
        let mut needed = 0u32;
        let mut count = 0u32;
        unsafe {
            let _ = EnumPrintersW(flags, PCWSTR::null(), 4, None, &mut needed, &mut count);
            if needed == 0 {
                return Ok(vec![]);
            }
            // PRINTER_INFO_4W-এর সারিবদ্ধতার জন্য u64 বাফার
            let mut buf = vec![0u64; (needed as usize).div_ceil(8)];
            let bytes = std::slice::from_raw_parts_mut(buf.as_mut_ptr() as *mut u8, buf.len() * 8);
            EnumPrintersW(flags, PCWSTR::null(), 4, Some(bytes), &mut needed, &mut count).map_err(|e| e.to_string())?;
            let infos = std::slice::from_raw_parts(buf.as_ptr() as *const PRINTER_INFO_4W, count as usize);
            let def = default_name();
            let mut out: Vec<PrinterInfo> = infos
                .iter()
                .map(|i| {
                    let name = from_pwstr(i.pPrinterName);
                    PrinterInfo { default: name == def, name }
                })
                .filter(|p| !p.name.is_empty())
                .collect();
            out.sort_by(|a, b| b.default.cmp(&a.default).then(a.name.cmp(&b.name)));
            Ok(out)
        }
    }

    pub fn raw(printer: &str, data: &[u8]) -> Result<(), String> {
        let wname = wide(printer);
        let mut doc_name = wide("Lifolo receipt");
        let mut datatype = wide("RAW");
        unsafe {
            let mut h = PRINTER_HANDLE::default();
            OpenPrinterW(PCWSTR(wname.as_ptr()), &mut h, None).map_err(|e| format!("open: {e}"))?;
            let info = DOC_INFO_1W { pDocName: PWSTR(doc_name.as_mut_ptr()), pOutputFile: PWSTR::null(), pDatatype: PWSTR(datatype.as_mut_ptr()) };
            let job = StartDocPrinterW(h, 1, &info);
            if job == 0 {
                let _ = ClosePrinter(h);
                return Err("start document failed".into());
            }
            let mut result = Ok(());
            if !StartPagePrinter(h).as_bool() {
                result = Err("start page failed".into());
            } else {
                let mut off = 0usize;
                while off < data.len() {
                    let chunk = &data[off..data.len().min(off + 64 * 1024)];
                    let mut written = 0u32;
                    if !WritePrinter(h, chunk.as_ptr() as *const core::ffi::c_void, chunk.len() as u32, &mut written).as_bool() || written == 0 {
                        result = Err("write failed".into());
                        break;
                    }
                    off += written as usize;
                }
                let _ = EndPagePrinter(h);
            }
            let _ = EndDocPrinter(h);
            let _ = ClosePrinter(h);
            result
        }
    }
}

#[cfg(not(windows))]
mod imp {
    use super::PrinterInfo;

    pub fn list() -> Result<Vec<PrinterInfo>, String> {
        Ok(vec![])
    }

    pub fn raw(_printer: &str, _data: &[u8]) -> Result<(), String> {
        Err("raw printing is only available on Windows".into())
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn decode() {
        assert_eq!(super::percent_decode("POS-80%20(USB)"), "POS-80 (USB)");
        assert_eq!(super::percent_decode("%E0%A6%AA"), "প");
        assert_eq!(super::percent_decode("a%2"), "a%2");
        assert_eq!(super::percent_decode("100%"), "100%");
    }
}
