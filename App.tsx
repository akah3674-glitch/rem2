import React, { useState, useRef } from 'react';
import {
  StyleSheet, View, StatusBar, TouchableOpacity,
  Text, SafeAreaView, TextInput,
} from 'react-native';
import { WebView } from 'react-native-webview';
import FloatingMailWindow from './src/components/FloatingMailWindow';
import RegisterScreen from './src/screens/RegisterScreen';

const HOME_URL = 'https://replit.com';

interface Tab {
  id: number;
  url: string;
}

let _tabId = 1;

export default function App() {
  const [showMail, setShowMail]         = useState(false);
  const [showRegister, setShowRegister] = useState(false);

  // Mỗi tab có id + url riêng — không chia sẻ state với nhau
  const [tabs, setTabs]           = useState<Tab[]>([{ id: 1, url: HOME_URL }]);
  const [activeId, setActiveId]   = useState(1);
  const [urlInput, setUrlInput]   = useState(HOME_URL);

  // ref riêng cho từng WebView instance
  const webRefs = useRef<Record<number, WebView | null>>({});

  const activeTab = tabs.find(t => t.id === activeId) ?? tabs[0];

  /* ── helpers ── */
  const normalizeUrl = (raw: string): string => {
    const s = raw.trim();
    if (!s) return activeTab.url;
    if (s.startsWith('http://') || s.startsWith('https://')) return s;
    if (s.includes('.') && !s.includes(' ')) return `https://${s}`;
    return `https://www.google.com/search?q=${encodeURIComponent(s)}`;
  };

  const navigate = (raw: string) => {
    const url = normalizeUrl(raw);
    setUrlInput(url);
    setTabs(prev => prev.map(t => t.id === activeId ? { ...t, url } : t));
  };

  const switchTab = (id: number) => {
    const tab = tabs.find(t => t.id === id);
    if (!tab) return;
    setActiveId(id);
    setUrlInput(tab.url);
  };

  const addTab = () => {
    const id = ++_tabId;
    // Tab mới mở cùng URL đang xem — hoàn toàn instance riêng biệt
    const url = activeTab.url;
    setTabs(prev => [...prev, { id, url }]);
    setActiveId(id);
    setUrlInput(url);
  };

  const closeTab = (id: number) => {
    if (tabs.length === 1) return;          // giữ ít nhất 1 tab
    const next = tabs.filter(t => t.id !== id);
    setTabs(next);
    if (activeId === id) {
      const fallback = next[next.length - 1];
      setActiveId(fallback.id);
      setUrlInput(fallback.url);
    }
    delete webRefs.current[id];
  };

  const handleVerifyLink = (url: string) => {
    // inject navigation vào tab đang active
    webRefs.current[activeId]?.injectJavaScript(
      `window.location.href = ${JSON.stringify(url)};true;`
    );
    setShowMail(false);
  };

  return (
    <SafeAreaView style={styles.root}>
      <StatusBar barStyle="light-content" backgroundColor="#0e1117" />

      {/* ── Header ── */}
      <View style={styles.header}>

        {/* Hàng 1: Brand + Mail/Reg */}
        <View style={styles.brandRow}>
          <Text style={styles.brand}>🌐 Rem2 Browser</Text>
          <View style={styles.actions}>
            <TouchableOpacity style={styles.btn}
              onPress={() => setShowRegister(v => !v)}>
              <Text style={styles.btnText}>➕ Reg</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.btn, showMail && styles.btnActive]}
              onPress={() => setShowMail(v => !v)}>
              <Text style={styles.btnText}>📧 Mail</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Hàng 2: URL bar + điều hướng */}
        <View style={styles.urlRow}>
          {/* ◀ Quay lại trong WebView history */}
          <TouchableOpacity style={styles.iconBtn}
            onPress={() => webRefs.current[activeId]?.goBack()}>
            <Text style={styles.iconTxt}>◀</Text>
          </TouchableOpacity>

          {/* URL input */}
          <TextInput
            style={styles.urlInput}
            value={urlInput}
            onChangeText={setUrlInput}
            onSubmitEditing={() => navigate(urlInput)}
            returnKeyType="go"
            selectTextOnFocus
            autoCapitalize="none"
            autoCorrect={false}
            keyboardType="url"
            placeholder="Nhập URL hoặc từ khoá..."
            placeholderTextColor="#4a5568"
          />

          {/* 🔄 Reload */}
          <TouchableOpacity style={styles.iconBtn}
            onPress={() => webRefs.current[activeId]?.reload()}>
            <Text style={styles.iconTxt}>🔄</Text>
          </TouchableOpacity>

          {/* 📑 Tab mới (instance hoàn toàn độc lập) */}
          <TouchableOpacity style={styles.iconBtn} onPress={addTab}>
            <Text style={styles.iconTxt}>📑</Text>
          </TouchableOpacity>
        </View>

        {/* Hàng 3: Tab bar — chỉ hiện khi có ≥2 tab */}
        {tabs.length > 1 && (
          <View style={styles.tabBar}>
            {tabs.map(tab => (
              <TouchableOpacity
                key={tab.id}
                style={[styles.tabItem, tab.id === activeId && styles.tabActive]}
                onPress={() => switchTab(tab.id)}>
                <Text style={styles.tabLabel} numberOfLines={1}>
                  {tab.url.replace(/^https?:\/\//, '').slice(0, 18) || 'Trang mới'}
                </Text>
                <TouchableOpacity onPress={() => closeTab(tab.id)} hitSlop={{ top: 6, bottom: 6, left: 6, right: 6 }}>
                  <Text style={styles.tabClose}>✕</Text>
                </TouchableOpacity>
              </TouchableOpacity>
            ))}
          </View>
        )}
      </View>

      {/* ── Content ── */}
      {showRegister ? (
        <RegisterScreen
          onClose={() => setShowRegister(false)}
          onVerifyLink={handleVerifyLink}
        />
      ) : (
        <View style={styles.webArea}>
          {tabs.map(tab => (
            <WebView
              key={tab.id}
              ref={r => { webRefs.current[tab.id] = r; }}
              source={{ uri: tab.url }}
              // Chỉ tab active chiếm không gian; tab khác thu về 0 (giữ state, không bị unmount)
              style={tab.id === activeId ? styles.webviewActive : styles.webviewHidden}
              javaScriptEnabled
              domStorageEnabled
              startInLoadingState
              userAgent="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
              onNavigationStateChange={state => {
                if (tab.id === activeId && state.url) {
                  setUrlInput(state.url);
                  setTabs(prev => prev.map(t =>
                    t.id === tab.id ? { ...t, url: state.url } : t
                  ));
                }
              }}
            />
          ))}
        </View>
      )}

      {/* ── Floating Mail ── */}
      {showMail && (
        <FloatingMailWindow
          onClose={() => setShowMail(false)}
          onVerifyLink={handleVerifyLink}
        />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  root:    { flex: 1, backgroundColor: '#0e1117' },

  /* Header */
  header: {
    backgroundColor: '#161b22',
    borderBottomWidth: 1,
    borderBottomColor: '#30363d',
    paddingBottom: 6,
  },

  /* Brand row */
  brandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 12,
    paddingTop: 8,
    paddingBottom: 4,
  },
  brand:   { color: '#58a6ff', fontWeight: 'bold', fontSize: 15 },
  actions: { flexDirection: 'row', gap: 8 },
  btn: {
    backgroundColor: '#21262d', borderRadius: 6,
    paddingHorizontal: 10, paddingVertical: 5,
    borderWidth: 1, borderColor: '#30363d',
  },
  btnActive:  { backgroundColor: '#1f6feb', borderColor: '#58a6ff' },
  btnText:    { color: '#c9d1d9', fontSize: 13 },

  /* URL row */
  urlRow: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 8,
    gap: 6,
  },
  urlInput: {
    flex: 1,
    backgroundColor: '#0d1117',
    color: '#c9d1d9',
    fontSize: 12,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#30363d',
  },
  iconBtn: {
    width: 34,
    height: 34,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: '#21262d',
    borderRadius: 7,
    borderWidth: 1,
    borderColor: '#30363d',
  },
  iconTxt: { fontSize: 15 },

  /* Tab bar */
  tabBar: {
    flexDirection: 'row',
    paddingHorizontal: 8,
    paddingTop: 4,
    gap: 6,
  },
  tabItem: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#21262d',
    borderRadius: 6,
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderWidth: 1,
    borderColor: '#30363d',
    gap: 4,
  },
  tabActive:  { backgroundColor: '#1f6feb', borderColor: '#58a6ff' },
  tabLabel:   { flex: 1, color: '#c9d1d9', fontSize: 11 },
  tabClose:   { color: '#8b949e', fontSize: 12 },

  /* WebViews */
  webArea:        { flex: 1 },
  webviewActive:  { flex: 1 },
  webviewHidden:  {
    width: 0, height: 0,
    position: 'absolute',
    opacity: 0,
  },
});
