import React, { useState, useRef } from 'react';
  import {
    StyleSheet, View, StatusBar, TouchableOpacity,
    Text, SafeAreaView, TextInput,
  } from 'react-native';
  import { WebView } from 'react-native-webview';
  import FloatingMailWindow from './src/components/FloatingMailWindow';
  import RegisterScreen from './src/screens/RegisterScreen';

  const HOME_URL = 'https://replit.com';

  interface Tab { id: number; url: string; incognito: boolean; }
  let _tabId = 1;

  export default function App() {
    const [showMail, setShowMail]         = useState(false);
    const [showRegister, setShowRegister] = useState(false);
    const [tabs, setTabs]     = useState<Tab[]>([{ id: 1, url: HOME_URL, incognito: false }]);
    const [activeId, setActiveId] = useState(1);
    const [urlInput, setUrlInput] = useState(HOME_URL);
    const webRefs = useRef<Record<number, WebView | null>>({});

    const activeTab = tabs.find(t => t.id === activeId) ?? tabs[0];

    const normalizeUrl = (raw: string) => {
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

    /* Tab mới luôn bắt đầu ở trang chủ + incognito → độc lập hoàn toàn */
    const addTab = () => {
      const id = ++_tabId;
      setTabs(prev => [...prev, { id, url: HOME_URL, incognito: true }]);
      setActiveId(id);
      setUrlInput(HOME_URL);
    };

    /* Đóng tab bằng long-press */
    const closeTab = (id: number) => {
      if (tabs.length === 1) return;
      const next = tabs.filter(t => t.id !== id);
      setTabs(next);
      if (activeId === id) {
        const f = next[next.length - 1];
        setActiveId(f.id); setUrlInput(f.url);
      }
      delete webRefs.current[id];
    };

    const handleVerifyLink = (url: string) => {
      webRefs.current[activeId]?.injectJavaScript(
        `window.location.href = ${JSON.stringify(url)};true;`
      );
      setShowMail(false);
    };

    return (
      <SafeAreaView style={styles.root}>
        <StatusBar barStyle="light-content" backgroundColor="#1a1f3a" />

        {/* ── Thanh header: nav | URL | actions ── */}
        <View style={styles.topBar}>

          {/* ◀ Quay lại */}
          <TouchableOpacity style={styles.iconBtn}
            onPress={() => webRefs.current[activeId]?.goBack()}>
            <Text style={styles.iconTxt}>◀</Text>
          </TouchableOpacity>

          {/* 🔄 Reload */}
          <TouchableOpacity style={styles.iconBtn}
            onPress={() => webRefs.current[activeId]?.reload()}>
            <Text style={styles.iconTxt}>🔄</Text>
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
            placeholder="Nhập URL..."
            placeholderTextColor="#6b7aaa"
          />

          {/* 📑 Tab mới (incognito) */}
          <TouchableOpacity style={styles.iconBtn} onPress={addTab}>
            <Text style={styles.iconTxt}>📑</Text>
          </TouchableOpacity>

          {/* ➕ Reg */}
          <TouchableOpacity style={[styles.iconBtn, showRegister && styles.iconBtnActive]}
            onPress={() => setShowRegister(v => !v)}>
            <Text style={styles.iconTxt}>➕</Text>
          </TouchableOpacity>

          {/* 📧 Mail */}
          <TouchableOpacity style={[styles.iconBtn, showMail && styles.iconBtnActive]}
            onPress={() => setShowMail(v => !v)}>
            <Text style={styles.iconTxt}>📧</Text>
          </TouchableOpacity>
        </View>

        {/* Tab bar — hiện khi có ≥2 tab; long-press để đóng */}
        {tabs.length > 1 && (
          <View style={styles.tabBar}>
            {tabs.map(tab => (
              <TouchableOpacity
                key={tab.id}
                style={[styles.tabItem, tab.id === activeId && styles.tabActive]}
                onPress={() => { setActiveId(tab.id); setUrlInput(tab.url); }}
                onLongPress={() => closeTab(tab.id)}>
                <Text style={styles.tabLabel} numberOfLines={1}>
                  {tab.incognito ? '🕵 ' : ''}
                  {tab.url.replace(/^https?:\/\//, '').slice(0, 16) || 'Trang mới'}
                </Text>
              </TouchableOpacity>
            ))}
          </View>
        )}

        {/* ── Content ── */}
        {showRegister ? (
          <RegisterScreen
            onClose={() => setShowRegister(false)}
            onVerifyLink={handleVerifyLink}
          />
        ) : (
          <View style={{ flex: 1 }}>
            {tabs.map(tab => (
              <WebView
                key={tab.id}
                ref={r => { webRefs.current[tab.id] = r; }}
                source={{ uri: tab.url }}
                style={tab.id === activeId ? styles.webviewActive : styles.webviewHidden}
                javaScriptEnabled
                domStorageEnabled
                incognito={tab.incognito}
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

        {/* Floating Mail */}
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
    root: { flex: 1, backgroundColor: '#0e1117' },

    /* Thanh header */
    topBar: {
      flexDirection: 'row',
      alignItems: 'center',
      backgroundColor: '#1a1f3a',
      paddingHorizontal: 8,
      paddingVertical: 7,
      gap: 5,
      borderBottomWidth: 1,
      borderBottomColor: '#2d3561',
    },
    urlInput: {
      flex: 1,
      height: 32,
      backgroundColor: '#0d1117',
      color: '#c9d1d9',
      fontSize: 12,
      paddingHorizontal: 9,
      borderRadius: 7,
      borderWidth: 1,
      borderColor: '#2d3561',
    },
    iconBtn: {
      width: 30,
      height: 30,
      alignItems: 'center',
      justifyContent: 'center',
      borderRadius: 6,
      backgroundColor: '#232946',
    },
    iconBtnActive: { backgroundColor: '#1f6feb' },
    iconTxt: { fontSize: 14 },

    /* Tab bar */
    tabBar: {
      flexDirection: 'row',
      backgroundColor: '#161b22',
      paddingHorizontal: 8,
      paddingVertical: 4,
      gap: 5,
      borderBottomWidth: 1,
      borderBottomColor: '#30363d',
    },
    tabItem: {
      flex: 1, flexDirection: 'row', alignItems: 'center',
      justifyContent: 'center',
      backgroundColor: '#21262d', borderRadius: 6,
      paddingHorizontal: 8, paddingVertical: 4,
      borderWidth: 1, borderColor: '#30363d',
    },
    tabActive: { backgroundColor: '#1f6feb', borderColor: '#58a6ff' },
    tabLabel:  { color: '#c9d1d9', fontSize: 11, textAlign: 'center' },

    /* WebViews */
    webviewActive:  { flex: 1 },
    webviewHidden:  { width: 0, height: 0, position: 'absolute', opacity: 0 },
  });
  