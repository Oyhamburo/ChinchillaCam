pub fn version_label() -> String {
    format!("ChinchillaCam {} (preliminar)", env!("CARGO_PKG_VERSION"))
}

#[cfg(test)]
mod tests {
    use super::version_label;

    #[test]
    fn visible_version_is_preliminary() {
        assert_eq!(version_label(), "ChinchillaCam 0.1.0 (preliminar)");
    }
}
