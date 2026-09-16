fn main() {
    let doc = std::fs::read_to_string("/tmp/appstate-mini.json").unwrap();
    let doc: serde_json::Value = serde_json::from_str(&doc).unwrap();
    let env = rsxm_config::split(&doc);
    let slice = env.for_module("rsxm-rules").unwrap();
    let rules: Vec<rsxm_rules::Rule> =
        serde_json::from_value(slice.value.get("rules").unwrap().clone()).unwrap();
    println!("parsed rules: {}", rules.len());
    for r in &rules {
        println!("  id={} suffixes={:?} target={:?}", r.id, r.suffixes, r.target);
    }
    let table = rsxm_rules::RuleTable::build(rules);
    let q = rsxm_rules::Query {
        domain: Some("www.news.cn".into()),
        ..Default::default()
    };
    println!("match: {:?}", table.match_query(&q).map(|m| m.target));
}
