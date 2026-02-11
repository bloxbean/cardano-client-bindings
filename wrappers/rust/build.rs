use std::env;

fn main() {
    let lib_path = env::var("CCL_LIB_PATH")
        .unwrap_or_else(|_| "../../core/build/native/nativeCompile".to_string());

    println!("cargo:rustc-link-search=native={}", lib_path);
    println!("cargo:rustc-link-lib=dylib=ccl");
    println!("cargo:rerun-if-changed=build.rs");
}
