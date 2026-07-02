import React, { useState, useRef } from 'react';
  import {
    StyleSheet, View, StatusBar, TouchableOpacity,
    Text, SafeAreaView, Animated, PanResponder,
  } from 'react-native';
  import { WebView } from 'react-native-webview';
  import FloatingMailWindow from './src/components/FloatingMailWindow';
  import RegisterScreen from './src/screens/RegisterScreen';

  export default function App() {
    const [showMail, setShowMail] = useState(false);
    const [showRegister, setShowRegister] = useState(false);
    const mainWebRef = useRef<WebView>(null);

    const handleVerifyLink = (url: string) => {
      mainWebRef.current?.injectJavaScript(`window.location.href = '${url}';`);
      setShowMail(false);
    };

    return (
      <SafeAreaView style={styles.root}>
        <StatusBar barStyle="light-content" backgroundColor="#0e1117" />

        {/* Top bar */}
        <View style={styles.topBar}>
          <Text style={styles.brand}>🌐 Rem2 Browser</Text>
          <View style={styles.actions}>
            <TouchableOpacity style={styles.btn}
              onPress={() => setShowRegister(!showRegister)}>
              <Text style={styles.btnText}>➕ Reg</Text>
            </TouchableOpacity>
            <TouchableOpacity style={[styles.btn, showMail && styles.btnActive]}
              onPress={() => setShowMail(!showMail)}>
              <Text style={styles.btnText}>📧 Mail</Text>
            </TouchableOpacity>
          </View>
        </View>

        {/* Main WebView */}
        {!showRegister ? (
          <WebView
            ref={mainWebRef}
            source={{ uri: 'https://replit.com' }}
            style={styles.webview}
            javaScriptEnabled
            domStorageEnabled
            startInLoadingState
            userAgent="Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
          />
        ) : (
          <RegisterScreen
            onClose={() => setShowRegister(false)}
            onVerifyLink={handleVerifyLink}
          />
        )}

        {/* Floating Mail Window */}
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
    topBar: {
      flexDirection: 'row', alignItems: 'center',
      justifyContent: 'space-between',
      backgroundColor: '#161b22', paddingHorizontal: 12, paddingVertical: 8,
      borderBottomWidth: 1, borderBottomColor: '#30363d',
    },
    brand: { color: '#58a6ff', fontWeight: 'bold', fontSize: 15 },
    actions: { flexDirection: 'row', gap: 8 },
    btn: {
      backgroundColor: '#21262d', borderRadius: 6,
      paddingHorizontal: 10, paddingVertical: 5,
      borderWidth: 1, borderColor: '#30363d',
    },
    btnActive: { backgroundColor: '#1f6feb', borderColor: '#58a6ff' },
    btnText: { color: '#c9d1d9', fontSize: 13 },
    webview: { flex: 1 },
  });