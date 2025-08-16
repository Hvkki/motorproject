import React, { useState, useEffect, useRef, useCallback } from 'react';

const AdvancedTennisGame = () => {
  const canvasRef = useRef(null);
  const joystickRef = useRef(null);
  const [gameState, setGameState] = useState({
    ball: { 
      x: 200, y: 180, dx: 0, dy: 0, 
      trail: [], inPlay: false, gravity: 0.3,
      bounce: 0.8, spin: 0
    },
    player: { 
      x: 50, y: 160, targetY: 160, velocityY: 0,
      jumping: false, onGround: true, racketAngle: 0,
      serving: false, hitAnimation: 0
    },
    ai: { 
      x: 350, y: 160, velocityY: 0, onGround: true,
      jumping: false, racketAngle: 0, hitAnimation: 0,
      reactionTime: 0, difficulty: 0.7
    },
    playerScore: 0,
    aiScore: 0,
    gameRunning: false,
    lastHit: null,
    gameSpeed: 1
  });
  
  const [controls, setControls] = useState({
    joystick: { active: false, centerX: 0, centerY: 0, knobX: 0, knobY: 0, directionX: 0, directionY: 0 },
    jumpPressed: false,
    hitPressed: false
  });
  
  const animationRef = useRef();

  const CANVAS_WIDTH = 400;
  const CANVAS_HEIGHT = 280;
  const BALL_SIZE = 10;
  const PLAYER_SIZE = 32;
  const RACKET_WIDTH = 50;
  const RACKET_HEIGHT = 8;
  const GROUND_Y = CANVAS_HEIGHT - 80;

  // Джойстик
  const JOYSTICK_SIZE = 80;
  const KNOB_SIZE = 30;

  // Серва м'яча тапом по екрану
  const handleCanvasTouch = useCallback((e) => {
    if (!gameState.gameRunning) return;
    
    e.preventDefault();
    const rect = canvasRef.current.getBoundingClientRect();
    const touch = e.touches?.[0] || e;
    const x = (touch.clientX - rect.left) * (CANVAS_WIDTH / rect.width);
    const y = (touch.clientY - rect.top) * (CANVAS_HEIGHT / rect.height);
    
    // Якщо м'яч не в грі, подаємо його
    if (!gameState.ball.inPlay) {
      const dx = (x - gameState.ball.x) * 0.15;
      const dy = (y - gameState.ball.y) * 0.15;
      
      setGameState(prev => ({
        ...prev,
        ball: {
          ...prev.ball,
          dx: Math.max(-8, Math.min(8, dx)),
          dy: Math.max(-6, Math.min(2, dy)),
          inPlay: true
        }
      }));
    }
  }, [gameState.gameRunning, gameState.ball.inPlay, gameState.ball.x, gameState.ball.y]);

  // Обробка джойстика
  const handleJoystickStart = useCallback((e) => {
    e.preventDefault();
    const rect = joystickRef.current.getBoundingClientRect();
    const centerX = rect.left + rect.width / 2;
    const centerY = rect.top + rect.height / 2;
    
    setControls(prev => ({
      ...prev,
      joystick: { ...prev.joystick, active: true, centerX, centerY }
    }));
  }, []);

  const handleJoystickMove = useCallback((e) => {
    if (!controls.joystick.active || !gameState.gameRunning) return;
    
    e.preventDefault();
    const touch = e.touches?.[0] || e;
    const deltaX = touch.clientX - controls.joystick.centerX;
    const deltaY = touch.clientY - controls.joystick.centerY;
    const maxDistance = JOYSTICK_SIZE / 2 - KNOB_SIZE / 2;
    
    const distance = Math.sqrt(deltaX * deltaX + deltaY * deltaY);
    const clampedDistance = Math.min(distance, maxDistance);
    const angle = Math.atan2(deltaY, deltaX);
    
    const clampedX = Math.cos(angle) * clampedDistance;
    const clampedY = Math.sin(angle) * clampedDistance;
    
    let directionX = 0, directionY = 0;
    if (Math.abs(clampedX) > 10) directionX = clampedX > 0 ? 1 : -1;
    if (Math.abs(clampedY) > 10) directionY = clampedY > 0 ? 1 : -1;
    
    setControls(prev => ({
      ...prev,
      joystick: { 
        ...prev.joystick, 
        knobX: clampedX, 
        knobY: clampedY,
        directionX, 
        directionY 
      }
    }));
  }, [controls.joystick.active, controls.joystick.centerX, controls.joystick.centerY, gameState.gameRunning]);

  const handleJoystickEnd = useCallback((e) => {
    e.preventDefault();
    setControls(prev => ({
      ...prev,
      joystick: { 
        ...prev.joystick, 
        active: false, 
        knobX: 0, 
        knobY: 0,
        directionX: 0, 
        directionY: 0 
      }
    }));
  }, []);

  // Кнопки
  const handleJumpPress = () => {
    setControls(prev => ({ ...prev, jumpPressed: true }));
    setTimeout(() => setControls(prev => ({ ...prev, jumpPressed: false })), 150);
  };

  const handleHitPress = () => {
    setControls(prev => ({ ...prev, hitPressed: true }));
    setTimeout(() => setControls(prev => ({ ...prev, hitPressed: false })), 200);
  };

  // Рух гравця
  useEffect(() => {
    if (!gameState.gameRunning) return;
    
    const moveInterval = setInterval(() => {
      setGameState(prev => {
        let newPlayer = { ...prev.player };
        
        // Горизонтальний рух
        if (controls.joystick.directionX !== 0) {
          newPlayer.x = Math.max(20, Math.min(180, newPlayer.x + controls.joystick.directionX * 4));
        }
        
        // Стрибок
        if (controls.jumpPressed && newPlayer.onGround) {
          newPlayer.velocityY = -12;
          newPlayer.jumping = true;
          newPlayer.onGround = false;
        }
        
        // Гравітація
        if (!newPlayer.onGround) {
          newPlayer.velocityY += 0.6;
          newPlayer.y += newPlayer.velocityY;
          
          if (newPlayer.y >= GROUND_Y) {
            newPlayer.y = GROUND_Y;
            newPlayer.velocityY = 0;
            newPlayer.onGround = true;
            newPlayer.jumping = false;
          }
        }
        
        // Анімація удару
        if (newPlayer.hitAnimation > 0) {
          newPlayer.hitAnimation--;
        }
        
        return { ...prev, player: newPlayer };
      });
    }, 16);
    
    return () => clearInterval(moveInterval);
  }, [controls.joystick.directionX, controls.jumpPressed, gameState.gameRunning]);

  // Простіший ШІ
  const updateSimpleAI = useCallback(() => {
    setGameState(prev => {
      let newAI = { ...prev.ai };
      const ball = prev.ball;
      
      // Рух до м'яча тільки коли він летить до ШІ
      if (ball.inPlay && ball.dx > 0) {
        const ballDistance = Math.abs(ball.x - newAI.x);
        const targetX = ball.x - 30;
        
        // Рух по X
        if (ballDistance > 30) {
          if (newAI.x < targetX && newAI.x < 380) {
            newAI.x += 2.5;
          } else if (newAI.x > targetX && newAI.x > 220) {
            newAI.x -= 2.5;
          }
        }
        
        // Стрибок якщо м'яч високо
        if (ball.y < GROUND_Y - 40 && ballDistance < 60 && newAI.onGround && Math.random() > 0.3) {
          newAI.velocityY = -10;
          newAI.jumping = true;
          newAI.onGround = false;
        }
      }
      
      // Гравітація для ШІ
      if (!newAI.onGround) {
        newAI.velocityY += 0.6;
        newAI.y += newAI.velocityY;
        
        if (newAI.y >= GROUND_Y) {
          newAI.y = GROUND_Y;
          newAI.velocityY = 0;
          newAI.onGround = true;
          newAI.jumping = false;
        }
      }
      
      // Анімація удару
      if (newAI.hitAnimation > 0) {
        newAI.hitAnimation--;
      }
      
      return { ...prev, ai: newAI };
    });
  }, []);

  // Ігровий цикл
  const gameLoop = useCallback(() => {
    if (!gameState.gameRunning) return;

    setGameState(prev => {
      let newState = { ...prev };
      
      // Рух м'яча
      if (newState.ball.inPlay) {
        newState.ball.x += newState.ball.dx;
        newState.ball.y += newState.ball.dy;
        
        // Гравітація для м'яча
        newState.ball.dy += newState.ball.gravity;
        
        // Відбиття від стін
        if (newState.ball.x <= 0 || newState.ball.x >= CANVAS_WIDTH - BALL_SIZE) {
          newState.ball.dx = -newState.ball.dx * 0.9;
        }
        
        // Відбиття від землі
        if (newState.ball.y >= GROUND_Y + 20) {
          newState.ball.dy = -newState.ball.dy * newState.ball.bounce;
          newState.ball.y = GROUND_Y + 20;
          newState.ball.dx *= 0.95; // Тертя
        }
        
        // Відбиття від верху
        if (newState.ball.y <= 0) {
          newState.ball.dy = -newState.ball.dy;
          newState.ball.y = 0;
        }
        
        // След м'яча
        newState.ball.trail.push({ x: newState.ball.x, y: newState.ball.y });
        if (newState.ball.trail.length > 6) {
          newState.ball.trail.shift();
        }
      }
      
      // Зіткнення з ракеткою гравця
      const playerRacketX = newState.player.x + 20;
      const playerRacketY = newState.player.y - 10;
      
      if (newState.ball.inPlay &&
          newState.ball.x >= playerRacketX - RACKET_WIDTH/2 && 
          newState.ball.x <= playerRacketX + RACKET_WIDTH/2 &&
          newState.ball.y >= playerRacketY - RACKET_HEIGHT/2 && 
          newState.ball.y <= playerRacketY + RACKET_HEIGHT/2 &&
          newState.lastHit !== 'player') {
        
        // Звичайний відбиток або потужний удар
        const power = controls.hitPressed ? 1.5 : 1.0;
        newState.ball.dx = Math.abs(newState.ball.dx) * power + 3;
        newState.ball.dy = (newState.ball.dy - 2) * power;
        newState.ball.spin = controls.hitPressed ? 0.5 : 0;
        newState.lastHit = 'player';
        
        newState.player.racketAngle = -45;
        newState.player.hitAnimation = 10;
      }
      
      // Зіткнення з ракеткою ШІ
      const aiRacketX = newState.ai.x - 20;
      const aiRacketY = newState.ai.y - 10;
      
      if (newState.ball.inPlay &&
          newState.ball.x >= aiRacketX - RACKET_WIDTH/2 && 
          newState.ball.x <= aiRacketX + RACKET_WIDTH/2 &&
          newState.ball.y >= aiRacketY - RACKET_HEIGHT/2 && 
          newState.ball.y <= aiRacketY + RACKET_HEIGHT/2 &&
          newState.lastHit !== 'ai') {
        
        newState.ball.dx = -Math.abs(newState.ball.dx) - 2;
        newState.ball.dy = (newState.ball.dy - 1) * 0.8;
        newState.lastHit = 'ai';
        
        newState.ai.racketAngle = 45;
        newState.ai.hitAnimation = 10;
      }
      
      // Повернення ракеток
      newState.player.racketAngle *= 0.8;
      newState.ai.racketAngle *= 0.8;
      
      // Голи
      if (newState.ball.x <= -20) {
        newState.aiScore += 1;
        newState.ball = { x: 100, y: 150, dx: 0, dy: 0, trail: [], inPlay: false, gravity: 0.3, bounce: 0.8, spin: 0 };
        newState.lastHit = null;
      } else if (newState.ball.x >= CANVAS_WIDTH + 20) {
        newState.playerScore += 1;
        newState.ball = { x: 300, y: 150, dx: 0, dy: 0, trail: [], inPlay: false, gravity: 0.3, bounce: 0.8, spin: 0 };
        newState.lastHit = null;
      }
      
      return newState;
    });

    updateSimpleAI();
  }, [gameState.gameRunning, controls.hitPressed, updateSimpleAI]);

  // Анімація
  useEffect(() => {
    if (gameState.gameRunning) {
      animationRef.current = setInterval(gameLoop, 16);
    } else {
      clearInterval(animationRef.current);
    }
    
    return () => clearInterval(animationRef.current);
  }, [gameState.gameRunning, gameLoop]);

  // Малювання
  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    
    const ctx = canvas.getContext('2d');
    
    // Очищення канвасу
    ctx.clearRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
    
    // Покращений фон з градієнтом
    const gradient = ctx.createLinearGradient(0, 0, 0, CANVAS_HEIGHT);
    gradient.addColorStop(0, '#1e3a8a'); // Темно-синій
    gradient.addColorStop(0.3, '#3b82f6'); // Синій
    gradient.addColorStop(0.7, '#60a5fa'); // Світло-синій
    gradient.addColorStop(1, '#10b981'); // Зелений
    ctx.fillStyle = gradient;
    ctx.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
    
    // Хмари
    ctx.fillStyle = 'rgba(255, 255, 255, 0.3)';
    for (let i = 0; i < 5; i++) {
      const x = (i * 100 + Date.now() * 0.01) % (CANVAS_WIDTH + 100) - 50;
      const y = 30 + Math.sin(i) * 10;
      ctx.beginPath();
      ctx.arc(x, y, 20, 0, Math.PI * 2);
      ctx.arc(x + 25, y, 15, 0, Math.PI * 2);
      ctx.arc(x + 45, y, 20, 0, Math.PI * 2);
      ctx.fill();
    }
    
    // Покращений корт з текстурою
    const courtGradient = ctx.createLinearGradient(0, GROUND_Y + 30, 0, CANVAS_HEIGHT);
    courtGradient.addColorStop(0, '#166534');
    courtGradient.addColorStop(1, '#15803d');
    ctx.fillStyle = courtGradient;
    ctx.fillRect(0, GROUND_Y + 30, CANVAS_WIDTH, 50);
    
    // Лінії корту
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 2;
    ctx.setLineDash([5, 5]);
    ctx.beginPath();
    ctx.moveTo(0, GROUND_Y + 55);
    ctx.lineTo(CANVAS_WIDTH, GROUND_Y + 55);
    ctx.stroke();
    ctx.setLineDash([]);
    
    // Покращена сітка
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(CANVAS_WIDTH/2 - 3, GROUND_Y - 30, 6, 60);
    
    // Тіні сітки
    for(let i = 0; i < 20; i++) {
      const alpha = 0.3 - (i * 0.01);
      ctx.fillStyle = `rgba(255,255,255,${alpha})`;
      ctx.fillRect(CANVAS_WIDTH/2 - 10 + Math.random()*20, GROUND_Y - 25 + i*3, 3, 1);
    }
    
    // Покращений след м'яча з ефектом світіння
    gameState.ball.trail.forEach((point, index) => {
      const alpha = (index + 1) / gameState.ball.trail.length * 0.8;
      const size = BALL_SIZE * alpha * 1.5;
      
      // Світіння
      ctx.shadowColor = '#ffff00';
      ctx.shadowBlur = 10;
      ctx.fillStyle = `rgba(255,255,100,${alpha * 0.5})`;
      ctx.beginPath();
      ctx.arc(point.x + BALL_SIZE/2, point.y + BALL_SIZE/2, size, 0, Math.PI * 2);
      ctx.fill();
      
      // Основний след
      ctx.shadowBlur = 0;
      ctx.fillStyle = `rgba(255,255,100,${alpha})`;
      ctx.beginPath();
      ctx.arc(point.x + BALL_SIZE/2, point.y + BALL_SIZE/2, size/2, 0, Math.PI * 2);
      ctx.fill();
    });
    
    // Покращений м'яч з 3D ефектом
    ctx.save();
    ctx.shadowColor = '#FFD700';
    ctx.shadowBlur = gameState.ball.inPlay ? 15 : 8;
    ctx.shadowOffsetX = 2;
    ctx.shadowOffsetY = 2;
    
    // Основний градієнт м'яча
    const ballGradient = ctx.createRadialGradient(
      gameState.ball.x + BALL_SIZE/2 - 2, gameState.ball.y + BALL_SIZE/2 - 2, 0,
      gameState.ball.x + BALL_SIZE/2, gameState.ball.y + BALL_SIZE/2, BALL_SIZE/2
    );
    ballGradient.addColorStop(0, '#ffffff');
    ballGradient.addColorStop(0.3, '#FFFF00');
    ballGradient.addColorStop(1, '#FFD700');
    ctx.fillStyle = ballGradient;
    
    ctx.beginPath();
    ctx.arc(gameState.ball.x + BALL_SIZE/2, gameState.ball.y + BALL_SIZE/2, BALL_SIZE/2, 0, Math.PI * 2);
    ctx.fill();
    
    // Додаткове світіння для м'яча в грі
    if (gameState.ball.inPlay) {
      ctx.shadowBlur = 20;
      ctx.fillStyle = 'rgba(255, 255, 100, 0.3)';
      ctx.beginPath();
      ctx.arc(gameState.ball.x + BALL_SIZE/2, gameState.ball.y + BALL_SIZE/2, BALL_SIZE, 0, Math.PI * 2);
      ctx.fill();
    }
    
    ctx.restore();
    
    if (!gameState.ball.inPlay) {
      ctx.fillStyle = 'rgba(255,255,255,0.9)';
      ctx.strokeStyle = '#000000';
      ctx.lineWidth = 1;
      ctx.font = 'bold 12px Arial';
      ctx.textAlign = 'center';
      ctx.strokeText('ТАП ДЛЯ ПОДАЧІ', gameState.ball.x + BALL_SIZE/2, gameState.ball.y - 15);
      ctx.fillText('ТАП ДЛЯ ПОДАЧІ', gameState.ball.x + BALL_SIZE/2, gameState.ball.y - 15);
    }
    
    // Покращений гравець з тінями
    ctx.save();
    ctx.translate(gameState.player.x, gameState.player.y);
    
    // Тінь гравця
    ctx.fillStyle = 'rgba(0,0,0,0.3)';
    ctx.beginPath();
    ctx.ellipse(0, 20, 15, 5, 0, 0, Math.PI * 2);
    ctx.fill();
    
    // Покращена ракетка гравця
    ctx.save();
    ctx.translate(20, -10);
    ctx.rotate(gameState.player.racketAngle * Math.PI / 180);
    
    // Ручка ракетки з градієнтом
    const handleGradient = ctx.createLinearGradient(-15, -2, 15, 2);
    handleGradient.addColorStop(0, '#8B4513');
    handleGradient.addColorStop(0.5, '#A0522D');
    handleGradient.addColorStop(1, '#8B4513');
    ctx.fillStyle = handleGradient;
    ctx.fillRect(-15, -2, 30, 4);
    
    // Покращені диски ракетки з ефектами
    ctx.fillStyle = '#FF4444';
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = 2;
    for (let i = 0; i < 5; i++) {
      const diskGradient = ctx.createRadialGradient(-20 + i * 10, 0, 0, -20 + i * 10, 0, 6);
      diskGradient.addColorStop(0, '#FF6666');
      diskGradient.addColorStop(1, '#FF4444');
      ctx.fillStyle = diskGradient;
      
      ctx.beginPath();
      ctx.arc(-20 + i * 10, 0, 6, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
    }
    
    ctx.restore();
    
    // Покращене тіло гравця
    const playerGradient = ctx.createLinearGradient(-15, -15, 15, 10);
    playerGradient.addColorStop(0, controls.hitPressed ? '#0056CC' : '#4169E1');
    playerGradient.addColorStop(1, controls.hitPressed ? '#003399' : '#1E40AF');
    ctx.fillStyle = playerGradient;
    ctx.fillRect(-15, -15, 30, 25);
    
    // Покращена голова
    const headGradient = ctx.createRadialGradient(0, -20, 0, 0, -20, 12);
    headGradient.addColorStop(0, '#FFE4B5');
    headGradient.addColorStop(1, '#DEB887');
    ctx.fillStyle = headGradient;
    ctx.beginPath();
    ctx.arc(0, -20, 12, 0, Math.PI * 2);
    ctx.fill();
    
    // Очі
    ctx.fillStyle = '#000000';
    ctx.beginPath();
    ctx.arc(-3, -22, 2, 0, Math.PI * 2);
    ctx.arc(3, -22, 2, 0, Math.PI * 2);
    ctx.fill();
    
    // Ефект удару з анімацією
    if (gameState.player.hitAnimation > 0) {
      const scale = 1 + (gameState.player.hitAnimation / 10) * 0.3;
      ctx.save();
      ctx.scale(scale, scale);
      ctx.fillStyle = '#FFD700';
      ctx.font = 'bold 14px Arial';
      ctx.fillText('POW!', 25, -25);
      ctx.restore();
    }
    
    ctx.restore();
    
    // Покращений ШІ
    ctx.save();
    ctx.translate(gameState.ai.x, gameState.ai.y);
    
    // Тінь ШІ
    ctx.fillStyle = 'rgba(0,0,0,0.3)';
    ctx.beginPath();
    ctx.ellipse(0, 20, 15, 5, 0, 0, Math.PI * 2);
    ctx.fill();
    
    // Покращена ракетка ШІ
    ctx.save();
    ctx.translate(-20, -10);
    ctx.rotate(gameState.ai.racketAngle * Math.PI / 180);
    
    // Ручка
    const aiHandleGradient = ctx.createLinearGradient(-15, -2, 15, 2);
    aiHandleGradient.addColorStop(0, '#8B4513');
    aiHandleGradient.addColorStop(0.5, '#A0522D');
    aiHandleGradient.addColorStop(1, '#8B4513');
    ctx.fillStyle = aiHandleGradient;
    ctx.fillRect(-15, -2, 30, 4);
    
    // Покращені диски
    ctx.strokeStyle = '#FFFFFF';
    ctx.lineWidth = 2;
    for (let i = 0; i < 5; i++) {
      const aiDiskGradient = ctx.createRadialGradient(-20 + i * 10, 0, 0, -20 + i * 10, 0, 6);
      aiDiskGradient.addColorStop(0, '#66FF66');
      aiDiskGradient.addColorStop(1, '#44FF44');
      ctx.fillStyle = aiDiskGradient;
      
      ctx.beginPath();
      ctx.arc(-20 + i * 10, 0, 6, 0, Math.PI * 2);
      ctx.fill();
      ctx.stroke();
    }
    
    ctx.restore();
    
    // Покращене тіло ШІ
    const aiGradient = ctx.createLinearGradient(-15, -15, 15, 10);
    aiGradient.addColorStop(0, '#DC143C');
    aiGradient.addColorStop(1, '#B22222');
    ctx.fillStyle = aiGradient;
    ctx.fillRect(-15, -15, 30, 25);
    
    // Покращена голова ШІ
    const aiHeadGradient = ctx.createRadialGradient(0, -20, 0, 0, -20, 12);
    aiHeadGradient.addColorStop(0, '#FFE4B5');
    aiHeadGradient.addColorStop(1, '#DEB887');
    ctx.fillStyle = aiHeadGradient;
    ctx.beginPath();
    ctx.arc(0, -20, 12, 0, Math.PI * 2);
    ctx.fill();
    
    // Очі ШІ
    ctx.fillStyle = '#000000';
    ctx.beginPath();
    ctx.arc(-3, -22, 2, 0, Math.PI * 2);
    ctx.arc(3, -22, 2, 0, Math.PI * 2);
    ctx.fill();
    
    // Ефект удару ШІ
    if (gameState.ai.hitAnimation > 0) {
      const scale = 1 + (gameState.ai.hitAnimation / 10) * 0.3;
      ctx.save();
      ctx.scale(scale, scale);
      ctx.fillStyle = '#FFD700';
      ctx.font = 'bold 14px Arial';
      ctx.fillText('HIT!', -35, -25);
      ctx.restore();
    }
    
    ctx.restore();
    
    // Покращений рахунок з ефектами
    ctx.fillStyle = '#ffffff';
    ctx.strokeStyle = '#000000';
    ctx.lineWidth = 4;
    ctx.font = 'bold 32px Arial';
    ctx.textAlign = 'center';
    ctx.strokeText(`${gameState.playerScore} : ${gameState.aiScore}`, CANVAS_WIDTH / 2, 40);
    
    // Градієнт для рахунку
    const scoreGradient = ctx.createLinearGradient(CANVAS_WIDTH / 2 - 50, 20, CANVAS_WIDTH / 2 + 50, 20);
    scoreGradient.addColorStop(0, '#FFD700');
    scoreGradient.addColorStop(0.5, '#FFFFFF');
    scoreGradient.addColorStop(1, '#FFD700');
    ctx.fillStyle = scoreGradient;
    ctx.fillText(`${gameState.playerScore} : ${gameState.aiScore}`, CANVAS_WIDTH / 2, 40);
    
    // Покращений заголовок
    ctx.fillStyle = '#FF6B6B';
    ctx.strokeStyle = '#ffffff';
    ctx.lineWidth = 3;
    ctx.font = 'bold 20px Arial';
    ctx.strokeText('🎾 DISK TENNIS 🎾', CANVAS_WIDTH / 2, 20);
    ctx.fillText('🎾 DISK TENNIS 🎾', CANVAS_WIDTH / 2, 20);
  }, [gameState, controls.hitPressed]);

  const startGame = () => {
    setGameState(prev => ({
      ...prev,
      gameRunning: true,
      ball: { x: 100, y: 150, dx: 0, dy: 0, trail: [], inPlay: false, gravity: 0.3, bounce: 0.8, spin: 0 }
    }));
  };

  const resetGame = () => {
    setGameState({
      ball: { x: 200, y: 180, dx: 0, dy: 0, trail: [], inPlay: false, gravity: 0.3, bounce: 0.8, spin: 0 },
      player: { x: 50, y: GROUND_Y, targetY: GROUND_Y, velocityY: 0, jumping: false, onGround: true, racketAngle: 0, serving: false, hitAnimation: 0 },
      ai: { x: 350, y: GROUND_Y, velocityY: 0, onGround: true, jumping: false, racketAngle: 0, hitAnimation: 0, reactionTime: 0, difficulty: 0.7 },
      playerScore: 0,
      aiScore: 0,
      gameRunning: false,
      lastHit: null,
      gameSpeed: 1
    });
  };

  return (
    <div className="flex flex-col items-center justify-center min-h-screen bg-gradient-to-b from-blue-500 via-purple-500 to-pink-500 px-4 py-2">
      {/* Заголовок */}
      <div className="text-center mb-3">
        <h1 className="text-3xl md:text-4xl font-bold text-white mb-2 drop-shadow-xl">
          🎾 DISK TENNIS 🎾
        </h1>
        <p className="text-sm text-white drop-shadow">Тап по екрану для подачі м'яча!</p>
      </div>
      
      {/* Ігрове поле */}
      <div className="relative mb-4 shadow-2xl rounded-xl overflow-hidden border-4 border-yellow-400">
        <canvas
          ref={canvasRef}
          width={CANVAS_WIDTH}
          height={CANVAS_HEIGHT}
          className="cursor-pointer"
          style={{ maxWidth: '100vw', height: 'auto' }}
          onTouchStart={handleCanvasTouch}
          onMouseDown={handleCanvasTouch}
        />
      </div>
      
      {/* Кнопки керування */}
      <div className="flex gap-4 mb-4">
        <button
          onClick={startGame}
          disabled={gameState.gameRunning}
          className="px-6 py-3 bg-green-500 text-white rounded-full font-bold text-lg shadow-xl hover:bg-green-600 disabled:bg-gray-500 transition-all transform hover:scale-105"
        >
          {gameState.gameRunning ? '🔥 ГРАЄ' : '🚀 СТАРТ'}
        </button>
        
        <button
          onClick={resetGame}
          className="px-6 py-3 bg-red-500 text-white rounded-full font-bold text-lg shadow-xl hover:bg-red-600 transition-all transform hover:scale-105"
        >
          🔄 РЕСТАРТ
        </button>
      </div>
      
      {/* Керування */}
      <div className="flex items-center gap-6 mb-4">
        {/* Джойстик */}
        <div className="text-center">
          <div 
            ref={joystickRef}
            className="relative bg-gradient-to-b from-gray-700 to-gray-900 rounded-full border-4 border-yellow-400 touch-none shadow-xl"
            style={{ 
              width: JOYSTICK_SIZE, 
              height: JOYSTICK_SIZE,
              opacity: gameState.gameRunning ? 1 : 0.6
            }}
            onTouchStart={handleJoystickStart}
            onTouchMove={handleJoystickMove}
            onTouchEnd={handleJoystickEnd}
            onMouseDown={handleJoystickStart}
            onMouseMove={handleJoystickMove}
            onMouseUp={handleJoystickEnd}
            onMouseLeave={handleJoystickEnd}
          >
            <div 
              className="absolute bg-gradient-to-b from-blue-400 to-blue-600 rounded-full border-3 border-white transition-all duration-75 shadow-lg"
              style={{
                width: KNOB_SIZE,
                height: KNOB_SIZE,
                left: '50%',
                top: '50%',
                transform: `translate(calc(-50% + ${controls.joystick.knobX}px), calc(-50% + ${controls.joystick.knobY}px))`,
                boxShadow: controls.joystick.active ? '0 0 20px rgba(59, 130, 246, 0.8)' : 'none'
              }}
            />
            
            <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
              <span className="text-yellow-300 font-bold text-xs">🕹️</span>
            </div>
          </div>
          <p className="text-xs text-white mt-1">РУХ</p>
        </div>
        
        {/* Кнопка стрибка */}
        <div className="text-center">
          <button
            onTouchStart={handleJumpPress}
            onMouseDown={handleJumpPress}
            className={`w-16 h-16 rounded-full border-4 border-white font-bold text-lg shadow-xl transition-all transform ${
              controls.jumpPressed 
                ? 'bg-yellow-400 scale-110' 
                : 'bg-blue-500 hover:bg-blue-600 hover:scale-105'
            }`}
            style={{ opacity: gameState.gameRunning ? 1 : 0.6 }}
          >
            ⬆️
          </button>
          <p className="text-xs text-white mt-1">СТРИБОК</p>
        </div>
        
        {/* Кнопка удару */}
        <div className="text-center">
          <button
            onTouchStart={handleHitPress}
            onMouseDown={handleHitPress}
            className={`w-16 h-16 rounded-full border-4 border-white font-bold text-lg shadow-xl transition-all transform ${
              controls.hitPressed 
                ? 'bg-red-400 scale-110' 
                : 'bg-green-500 hover:bg-green-600 hover:scale-105'
            }`}
            style={{ opacity: gameState.gameRunning ? 1 : 0.6 }}
          >
            💥
          </button>
          <p className="text-xs text-white mt-1">УДАР</p>
        </div>
      </div>
      
      {/* Інструкції */}
      <div className="text-center text-white text-sm opacity-80">
        <p>🎮 Використовуйте джойстик для руху</p>
        <p>⬆️ Стрибайте для досягнення високих м'ячів</p>
        <p>💥 Потужний удар для швидких подач</p>
        <p>👆 Тап по екрану для подачі м'яча</p>
      </div>
    </div>
  );
};

export default AdvancedTennisGame;