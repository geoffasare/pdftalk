import { useState } from 'react';

export default function PhoneWireframe() {
  const [isPlaying, setIsPlaying] = useState(false);
  const [activeButton, setActiveButton] = useState(null);
  const [currentPage, setCurrentPage] = useState(1);
  const totalPages = 5;

  const handleButtonClick = (buttonName) => {
    setActiveButton(buttonName);
    if (buttonName === 'play') {
      setIsPlaying(!isPlaying);
    }
    if (buttonName === 'prev' && currentPage > 1) {
      setCurrentPage(currentPage - 1);
    }
    if (buttonName === 'next' && currentPage < totalPages) {
      setCurrentPage(currentPage + 1);
    }
    setTimeout(() => setActiveButton(null), 150);
  };

  return (
    <div style={{
      minHeight: '100vh',
      background: 'linear-gradient(135deg, #f0f0f0 0%, #e8e8e8 50%, #d8d8d8 100%)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'center',
      fontFamily: "'SF Pro Display', -apple-system, BlinkMacSystemFont, sans-serif",
      padding: '20px'
    }}>
      {/* Phone Frame */}
      <div style={{
        width: '320px',
        height: '640px',
        background: 'linear-gradient(180deg, #fafafa 0%, #ffffff 100%)',
        borderRadius: '40px',
        boxShadow: `
          0 25px 60px rgba(0, 0, 0, 0.15),
          0 10px 25px rgba(0, 0, 0, 0.1),
          inset 0 1px 0 rgba(255, 255, 255, 0.8),
          inset 0 -1px 0 rgba(0, 0, 0, 0.05)
        `,
        border: '8px solid #1a1a1a',
        position: 'relative',
        overflow: 'hidden',
        display: 'flex',
        flexDirection: 'column'
      }}>
        {/* Notch */}
        <div style={{
          position: 'absolute',
          top: 0,
          left: '50%',
          transform: 'translateX(-50%)',
          width: '120px',
          height: '28px',
          background: '#1a1a1a',
          borderRadius: '0 0 20px 20px',
          zIndex: 10
        }}>
          <div style={{
            position: 'absolute',
            right: '20px',
            top: '10px',
            width: '8px',
            height: '8px',
            background: '#333',
            borderRadius: '50%',
            boxShadow: 'inset 0 0 2px rgba(100, 100, 255, 0.3)'
          }} />
        </div>

        {/* Screen Area */}
        <div style={{
          flex: 1,
          margin: '12px',
          marginTop: '40px',
          background: `
            linear-gradient(135deg, #f8f9fa 0%, #ffffff 100%)
          `,
          borderRadius: '24px',
          position: 'relative',
          overflow: 'hidden',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center'
        }}>
          {/* Wireframe Grid Pattern */}
          <div style={{
            position: 'absolute',
            inset: 0,
            backgroundImage: `
              linear-gradient(rgba(200, 200, 200, 0.3) 1px, transparent 1px),
              linear-gradient(90deg, rgba(200, 200, 200, 0.3) 1px, transparent 1px)
            `,
            backgroundSize: '20px 20px',
            opacity: 0.5
          }} />

          {/* Content Placeholder */}
          <div style={{
            textAlign: 'center',
            color: '#999',
            zIndex: 1
          }}>
            <div style={{
              width: '80px',
              height: '80px',
              border: '2px dashed #ccc',
              borderRadius: '50%',
              margin: '0 auto 16px',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center'
            }}>
              <svg width="32" height="32" viewBox="0 0 24 24" fill="none" stroke="#bbb" strokeWidth="1.5">
                <rect x="3" y="3" width="18" height="18" rx="2" />
                <circle cx="8.5" cy="8.5" r="1.5" />
                <path d="M21 15l-5-5L5 21" />
              </svg>
            </div>
            <p style={{ 
              margin: 0, 
              fontSize: '12px',
              letterSpacing: '2px',
              textTransform: 'uppercase',
              fontWeight: 500
            }}>
              Screen Content
            </p>
          </div>
        </div>

        {/* Bottom Control Bar */}
        <div style={{
          height: '100px',
          background: 'linear-gradient(180deg, #ffffff 0%, #f5f5f5 100%)',
          borderTop: '1px solid rgba(0, 0, 0, 0.08)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          gap: '8px',
          padding: '0 12px',
          borderRadius: '0 0 32px 32px'
        }}>
          {/* Upload Button */}
          <button
            onClick={() => handleButtonClick('upload')}
            style={{
              width: '42px',
              height: '42px',
              borderRadius: '50%',
              border: '2px solid #333',
              background: activeButton === 'upload'
                ? 'linear-gradient(180deg, #e0e0e0 0%, #d0d0d0 100%)'
                : 'linear-gradient(180deg, #ffffff 0%, #f0f0f0 100%)',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'all 0.15s ease',
              boxShadow: activeButton === 'upload'
                ? 'inset 0 2px 4px rgba(0,0,0,0.1)'
                : '0 2px 6px rgba(0,0,0,0.1)'
            }}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#333" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
              <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
              <polyline points="17 8 12 3 7 8" />
              <line x1="12" y1="3" x2="12" y2="15" />
            </svg>
          </button>

          {/* Stop Button */}
          <button
            onClick={() => handleButtonClick('stop')}
            style={{
              width: '42px',
              height: '42px',
              borderRadius: '50%',
              border: '2px solid #333',
              background: activeButton === 'stop' 
                ? 'linear-gradient(180deg, #e0e0e0 0%, #d0d0d0 100%)'
                : 'linear-gradient(180deg, #ffffff 0%, #f0f0f0 100%)',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'all 0.15s ease',
              boxShadow: activeButton === 'stop'
                ? 'inset 0 2px 4px rgba(0,0,0,0.1)'
                : '0 2px 6px rgba(0,0,0,0.1)'
            }}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="#333" stroke="none">
              <rect x="6" y="6" width="12" height="12" rx="1" />
            </svg>
          </button>

          {/* Previous Button */}
          <button
            onClick={() => handleButtonClick('prev')}
            style={{
              width: '42px',
              height: '42px',
              borderRadius: '50%',
              border: '2px solid #333',
              background: activeButton === 'prev'
                ? 'linear-gradient(180deg, #e0e0e0 0%, #d0d0d0 100%)'
                : 'linear-gradient(180deg, #ffffff 0%, #f0f0f0 100%)',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'all 0.15s ease',
              boxShadow: activeButton === 'prev'
                ? 'inset 0 2px 4px rgba(0,0,0,0.1)'
                : '0 2px 6px rgba(0,0,0,0.1)',
              opacity: currentPage === 1 ? 0.4 : 1
            }}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="#333" stroke="none">
              <path d="M6 6h2v12H6V6zm3.5 6l8.5 6V6l-8.5 6z" />
            </svg>
          </button>

          {/* Page Indicator */}
          <div style={{
            display: 'flex',
            flexDirection: 'column',
            alignItems: 'center',
            justifyContent: 'center',
            minWidth: '40px',
            padding: '0 2px'
          }}>
            <span style={{
              fontSize: '16px',
              fontWeight: '600',
              color: '#1a1a1a',
              letterSpacing: '-0.5px'
            }}>
              {currentPage}
              <span style={{ 
                color: '#999', 
                fontWeight: '400',
                fontSize: '12px',
                margin: '0 1px'
              }}>/</span>
              {totalPages}
            </span>
            <span style={{
              fontSize: '8px',
              textTransform: 'uppercase',
              letterSpacing: '1px',
              color: '#999',
              marginTop: '1px'
            }}>
              page
            </span>
          </div>

          {/* Next Button */}
          <button
            onClick={() => handleButtonClick('next')}
            style={{
              width: '42px',
              height: '42px',
              borderRadius: '50%',
              border: '2px solid #333',
              background: activeButton === 'next'
                ? 'linear-gradient(180deg, #e0e0e0 0%, #d0d0d0 100%)'
                : 'linear-gradient(180deg, #ffffff 0%, #f0f0f0 100%)',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'all 0.15s ease',
              boxShadow: activeButton === 'next'
                ? 'inset 0 2px 4px rgba(0,0,0,0.1)'
                : '0 2px 6px rgba(0,0,0,0.1)',
              opacity: currentPage === totalPages ? 0.4 : 1
            }}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="#333" stroke="none">
              <path d="M18 6h-2v12h2V6zm-3.5 6L6 6v12l8.5-6z" />
            </svg>
          </button>

          {/* Menu/Settings Button */}
          <button
            onClick={() => handleButtonClick('menu')}
            style={{
              width: '42px',
              height: '42px',
              borderRadius: '50%',
              border: '2px solid #333',
              background: activeButton === 'menu'
                ? 'linear-gradient(180deg, #e0e0e0 0%, #d0d0d0 100%)'
                : 'linear-gradient(180deg, #ffffff 0%, #f0f0f0 100%)',
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              transition: 'all 0.15s ease',
              boxShadow: activeButton === 'menu'
                ? 'inset 0 2px 4px rgba(0,0,0,0.1)'
                : '0 2px 6px rgba(0,0,0,0.1)'
            }}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="#333" strokeWidth="2">
              <circle cx="12" cy="12" r="1" fill="#333" />
              <circle cx="12" cy="6" r="1" fill="#333" />
              <circle cx="12" cy="18" r="1" fill="#333" />
              <circle cx="6" cy="12" r="1" fill="#333" />
              <circle cx="18" cy="12" r="1" fill="#333" />
            </svg>
          </button>
        </div>

        {/* Home Indicator */}
        <div style={{
          position: 'absolute',
          bottom: '8px',
          left: '50%',
          transform: 'translateX(-50%)',
          width: '120px',
          height: '4px',
          background: '#333',
          borderRadius: '2px'
        }} />
      </div>

      {/* Label */}
      <div style={{
        position: 'fixed',
        bottom: '20px',
        left: '50%',
        transform: 'translateX(-50%)',
        color: '#999',
        fontSize: '11px',
        letterSpacing: '3px',
        textTransform: 'uppercase',
        fontWeight: 500
      }}>
        Media Player Wireframe
      </div>
    </div>
  );
}
