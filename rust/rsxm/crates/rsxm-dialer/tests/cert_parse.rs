use rsxm_dialer::hello;

#[test]
fn parses_go_generated_cert() {
    let der = hello::test_cert_der();
    let sig = hello::debug_sig(&der);
    assert_eq!(sig.len(), 64, "sig len");
    let pk = hello::debug_pub(&der);
    hello::debug_walk_tbs(&der);
    assert!(pk.iter().any(|b| *b != 0), "pubkey should be non-zero");
}
