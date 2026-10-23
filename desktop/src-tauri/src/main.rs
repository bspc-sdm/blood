// Lifolo ডেস্কটপ — কনসোল জানালা ছাড়া (রিলিজে)
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    lifolo_desktop_lib::run()
}
