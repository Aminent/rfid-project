const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const { body, validationResult, param } = require('express-validator');
require('dotenv').config();

const app = express();
const PORT = process.env.PORT || 3001;

// Middleware
app.use(helmet());
app.use(cors({
  origin: process.env.ALLOWED_ORIGINS?.split(',') || ['http://localhost:3001'],
  credentials: true
}));
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

// Rate limiting
const limiter = rateLimit({
  windowMs: 15 * 60 * 1000, // 15 minutes
  max: 1000, // limit each IP to 100 requests per windowMs
  message: 'Too many requests from this IP, please try again later.'
});
app.use('/api/', limiter);

// In-memory storage (replace with database in production)
let rfidTags = new Map();
let assets = new Map();
let tagReadings = [];

// Helper functions
const generateId = () => Date.now().toString(36) + Math.random().toString(36).substr(2);

const validateTag = (tagData) => {
  if (!tagData.epc || typeof tagData.epc !== 'string') {
    return { valid: false, error: 'EPC is required and must be a string' };
  }
  if (tagData.epc.length < 4 || tagData.epc.length > 96) {
    return { valid: false, error: 'EPC must be between 4 and 96 characters' };
  }
  return { valid: true };
};

// Update the tag reading validation
const validateTagReading = (reading) => {
  if (!reading.epc || typeof reading.epc !== 'string') {
    return { valid: false, error: 'EPC is required and must be a string' };
  }
  if (reading.epc.length < 4 || reading.epc.length > 96) {
    return { valid: false, error: 'EPC must be between 4 and 96 characters' };
  }
  if (reading.rssi && typeof reading.rssi !== 'number') {
    return { valid: false, error: 'RSSI must be a number' };
  }
  if (reading.timestamp && isNaN(Date.parse(reading.timestamp))) {
    return { valid: false, error: 'Invalid timestamp format' };
  }
  if (reading.department && typeof reading.department !== 'string') {
    return { valid: false, error: 'Department must be a string' };
  }
  if (reading.roomNumber && typeof reading.roomNumber !== 'string') {
    return { valid: false, error: 'Room number must be a string' };
  }
  if (reading.floor && typeof reading.floor !== 'string') {
    return { valid: false, error: 'Floor must be a string' };
  }
  return { valid: true };
};

// Error handling middleware
const handleValidationErrors = (req, res, next) => {
  const errors = validationResult(req);
  if (!errors.isEmpty()) {
    return res.status(400).json({
      success: false,
      message: 'Validation failed',
      errors: errors.array()
    });
  }
  next();
};

// Routes

// Health check
app.get('/health', (req, res) => {
  res.json({
    success: true,
    message: 'RFID Backend Server is running',
    timestamp: new Date().toISOString(),
    uptime: process.uptime()
  });
});

// Get all RFID tags
app.get('/api/tags', (req, res) => {
  try {
    const { page = 1, limit = 50, search } = req.query;
    const offset = (page - 1) * limit;
    
    let tags = Array.from(rfidTags.values());
    
    // Search functionality
    if (search) {
      const searchLower = search.toLowerCase();
      tags = tags.filter(tag => 
        tag.epc.toLowerCase().includes(searchLower) ||
        (tag.assetInfo && tag.assetInfo.name && tag.assetInfo.name.toLowerCase().includes(searchLower))
      );
    }
    
    const total = tags.length;
    const paginatedTags = tags.slice(offset, offset + parseInt(limit));
    
    res.json({
      success: true,
      data: {
        tags: paginatedTags,
        pagination: {
          page: parseInt(page),
          limit: parseInt(limit),
          total,
          pages: Math.ceil(total / limit)
        }
      }
    });
  } catch (error) {
    console.error('Error fetching tags:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch tags',
      error: error.message
    });
  }
});

// Get specific RFID tag
app.get('/api/tags/:epc', 
  param('epc').isLength({ min: 4, max: 96 }).withMessage('Invalid EPC format'),
  handleValidationErrors,
  (req, res) => {
    try {
      const { epc } = req.params;
      const tag = rfidTags.get(epc);
      
      if (!tag) {
        return res.status(404).json({
          success: false,
          message: 'Tag not found'
        });
      }
      
      res.json({
        success: true,
        data: tag
      });
    } catch (error) {
      console.error('Error fetching tag:', error);
      res.status(500).json({
        success: false,
        message: 'Failed to fetch tag',
        error: error.message
      });
    }
  }
);

// Record RFID tag reading
app.post('/api/tags/reading',
  [
    body('epc').isLength({ min: 4, max: 96 }).withMessage('EPC must be between 4 and 96 characters'),
    body('rssi').optional().isNumeric().withMessage('RSSI must be a number'),
    body('timestamp').optional().isISO8601().withMessage('Invalid timestamp format'),
    body('department').optional().isString().withMessage('Department must be a string'),
    body('roomNumber').optional().isString().withMessage('Room number must be a string'),
    body('floor').optional().isString().withMessage('Floor must be a string'),
    body('location').optional().isString().withMessage('Location must be a string')
  ],
  handleValidationErrors,
  (req, res) => {
    try {
      const { epc, rssi, timestamp, department, roomNumber, floor, location, deviceId } = req.body;
      
      const tagValidation = validateTag({ epc });
      if (!tagValidation.valid) {
        return res.status(400).json({
          success: false,
          message: tagValidation.error
        });
      }
      
      const now = new Date();
      const reading = {
        id: generateId(),
        epc,
        rssi: rssi || null,
        timestamp: timestamp ? new Date(timestamp) : now,
        department: department || 'Unassigned',
        roomNumber: roomNumber || 'Unknown',
        floor: floor || '0',
        location: location || null,
        deviceId: deviceId || null,
        createdAt: now
      };
      
      // Store reading
      tagReadings.push(reading);
      
      // Update or create tag record
      if (rfidTags.has(epc)) {
        const existingTag = rfidTags.get(epc);
        existingTag.lastSeen = reading.timestamp;
        existingTag.readCount = (existingTag.readCount || 0) + 1;
        existingTag.lastRssi = rssi;
        existingTag.lastLocation = location;
        existingTag.updatedAt = now;
      } else {
        rfidTags.set(epc, {
          epc,
          firstSeen: reading.timestamp,
          lastSeen: reading.timestamp,
          readCount: 1,
          lastRssi: rssi,
          lastLocation: location,
          assetInfo: null,
          createdAt: now,
          updatedAt: now
        });
      }
      
      // Keep only last 10000 readings to prevent memory overflow
      if (tagReadings.length > 10000) {
        tagReadings = tagReadings.slice(-5000);
      }
      
      res.status(201).json({
        success: true,
        message: 'Tag reading recorded successfully',
        data: reading
      });
    } catch (error) {
      console.error('Error recording tag reading:', error);
      res.status(500).json({
        success: false,
        message: 'Failed to record tag reading',
        error: error.message
      });
    }
  }
);

// Assign asset to RFID tag
app.post('/api/tags/:epc/asset',
  [
    param('epc').isLength({ min: 4, max: 96 }).withMessage('Invalid EPC format'),
    body('name').isString().isLength({ min: 1, max: 255 }).withMessage('Asset name is required and must be 1-255 characters'),
    body('description').optional().isString().isLength({ max: 1000 }).withMessage('Description must be less than 1000 characters'),
    body('department').isString().isLength({ min: 1, max: 100 }).withMessage('Department is required and must be 1-100 characters'),
    body('roomNumber').isString().isLength({ min: 1, max: 50 }).withMessage('Room number is required and must be 1-50 characters'),
    body('floor').isString().isLength({ min: 1, max: 20 }).withMessage('Floor is required and must be 1-20 characters'),
    body('category').optional().isString().isLength({ max: 100 }).withMessage('Category must be less than 100 characters'),
    body('value').optional().isNumeric().withMessage('Value must be a number'),
    body('location').optional().isString().isLength({ max: 255 }).withMessage('Location must be less than 255 characters'),
    body('status').optional().isString().isIn(['Available', 'In-Use', 'Maintenance']).withMessage('Status must be Available, In-Use, or Maintenance'),
    body('owner').optional().isString().isLength({ max: 255 }).withMessage('Owner must be less than 255 characters')
  ],
  handleValidationErrors,
  (req, res) => {
    try {
      const { epc } = req.params;
      const { name, description, department, roomNumber, floor, category, value, location, status, owner } = req.body;
      
      // Check if tag exists, if not create it
      if (!rfidTags.has(epc)) {
        const now = new Date();
        rfidTags.set(epc, {
          epc,
          firstSeen: now,
          lastSeen: now,
          readCount: 0,
          lastRssi: null,
          lastLocation: null,
          assetInfo: null,
          createdAt: now,
          updatedAt: now
        });
      }
      
      const assetId = generateId();
      const asset = {
        id: assetId,
        epc,
        name,
        description: description || null,
        department,
        roomNumber,
        floor,
        category: category || null,
        value: value || null,
        location: location || null,
        status: status || 'Available',
        owner: owner || null,
        createdAt: new Date(),
        updatedAt: new Date()
      };
      
      // Store asset
      assets.set(assetId, asset);
      
      // Update tag with asset info
      const tag = rfidTags.get(epc);
      tag.assetInfo = asset;
      tag.updatedAt = new Date();
      
      res.status(201).json({
        success: true,
        message: 'Asset assigned to tag successfully',
        data: asset
      });
    } catch (error) {
      console.error('Error assigning asset:', error);
      res.status(500).json({
        success: false,
        message: 'Failed to assign asset to tag',
        error: error.message
      });
    }
  }
);

// Update asset information
app.put('/api/assets/:assetId',
  [
    param('assetId').isString().withMessage('Invalid asset ID'),
    body('name').optional().isString().isLength({ min: 1, max: 255 }).withMessage('Asset name must be 1-255 characters'),
    body('description').optional().isString().isLength({ max: 1000 }).withMessage('Description must be less than 1000 characters'),
    body('category').optional().isString().isLength({ max: 100 }).withMessage('Category must be less than 100 characters'),
    body('value').optional().isNumeric().withMessage('Value must be a number'),
    body('location').optional().isString().isLength({ max: 255 }).withMessage('Location must be less than 255 characters'),
    body('owner').optional().isString().isLength({ max: 255 }).withMessage('Owner must be less than 255 characters')
  ],
  handleValidationErrors,
  (req, res) => {
    try {
      const { assetId } = req.params;
      const updateData = req.body;
      
      const asset = assets.get(assetId);
      if (!asset) {
        return res.status(404).json({
          success: false,
          message: 'Asset not found'
        });
      }
      
      // Update asset
      Object.keys(updateData).forEach(key => {
        if (updateData[key] !== undefined) {
          asset[key] = updateData[key];
        }
      });
      asset.updatedAt = new Date();
      
      // Update tag's asset info
      const tag = rfidTags.get(asset.epc);
      if (tag) {
        tag.assetInfo = asset;
        tag.updatedAt = new Date();
      }
      
      res.json({
        success: true,
        message: 'Asset updated successfully',
        data: asset
      });
    } catch (error) {
      console.error('Error updating asset:', error);
      res.status(500).json({
        success: false,
        message: 'Failed to update asset',
        error: error.message
      });
    }
  }
);

// Get tag readings history
app.get('/api/tags/:epc/readings',
  [
    param('epc').isLength({ min: 4, max: 96 }).withMessage('Invalid EPC format'),
  ],
  handleValidationErrors,
  (req, res) => {
    try {
      const { epc } = req.params;
      const { page = 1, limit = 50, startDate, endDate } = req.query;
      const offset = (page - 1) * limit;
      
      let readings = tagReadings.filter(reading => reading.epc === epc);
      
      // Date filtering
      if (startDate) {
        const start = new Date(startDate);
        readings = readings.filter(reading => reading.timestamp >= start);
      }
      if (endDate) {
        const end = new Date(endDate);
        readings = readings.filter(reading => reading.timestamp <= end);
      }
      
      // Sort by timestamp descending
      readings.sort((a, b) => b.timestamp - a.timestamp);
      
      const total = readings.length;
      const paginatedReadings = readings.slice(offset, offset + parseInt(limit));
      
      res.json({
        success: true,
        data: {
          readings: paginatedReadings,
          pagination: {
            page: parseInt(page),
            limit: parseInt(limit),
            total,
            pages: Math.ceil(total / limit)
          }
        }
      });
    } catch (error) {
      console.error('Error fetching tag readings:', error);
      res.status(500).json({
        success: false,
        message: 'Failed to fetch tag readings',
        error: error.message
      });
    }
  }
);

// Get all assets
app.get('/api/assets', (req, res) => {
  try {
    const { page = 1, limit = 50, category, search } = req.query;
    const offset = (page - 1) * limit;
    
    let assetList = Array.from(assets.values());
    
    // Category filtering
    if (category) {
      assetList = assetList.filter(asset => asset.category === category);
    }
    
    // Search functionality
    if (search) {
      const searchLower = search.toLowerCase();
      assetList = assetList.filter(asset => 
        asset.name.toLowerCase().includes(searchLower) ||
        (asset.description && asset.description.toLowerCase().includes(searchLower)) ||
        asset.epc.toLowerCase().includes(searchLower)
      );
    }
    
    const total = assetList.length;
    const paginatedAssets = assetList.slice(offset, offset + parseInt(limit));
    
    res.json({
      success: true,
      data: {
        assets: paginatedAssets,
        pagination: {
          page: parseInt(page),
          limit: parseInt(limit),
          total,
          pages: Math.ceil(total / limit)
        }
      }
    });
  } catch (error) {
    console.error('Error fetching assets:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch assets',
      error: error.message
    });
  }
});

// Delete asset
app.delete('/api/assets/:assetId',
  param('assetId').isString().withMessage('Invalid asset ID'),
  handleValidationErrors,
  (req, res) => {
    try {
      const { assetId } = req.params;
      
      const asset = assets.get(assetId);
      if (!asset) {
        return res.status(404).json({
          success: false,
          message: 'Asset not found'
        });
      }
      
      // Remove asset from tag
      const tag = rfidTags.get(asset.epc);
      if (tag) {
        tag.assetInfo = null;
        tag.updatedAt = new Date();
      }
      
      // Delete asset
      assets.delete(assetId);
      
      res.json({
        success: true,
        message: 'Asset deleted successfully'
      });
    } catch (error) {
      console.error('Error deleting asset:', error);
      res.status(500).json({
        success: false,
        message: 'Failed to delete asset',
        error: error.message
      });
    }
  }
);

// Get dashboard statistics
app.get('/api/dashboard/stats', (req, res) => {
  try {
    const now = new Date();
    const last24Hours = new Date(now.getTime() - 24 * 60 * 60 * 1000);
    
    const totalTags = rfidTags.size;
    const totalAssets = assets.size;
    const totalReadings = tagReadings.length;
    
    const recentReadings = tagReadings.filter(reading => reading.timestamp >= last24Hours);
    const activeTagsLast24h = new Set(recentReadings.map(reading => reading.epc)).size;
    
    const unassignedTags = Array.from(rfidTags.values()).filter(tag => !tag.assetInfo).length;
    
    res.json({
      success: true,
      data: {
        totalTags,
        totalAssets,
        totalReadings,
        activeTagsLast24h,
        unassignedTags,
        readingsLast24h: recentReadings.length
      }
    });
  } catch (error) {
    console.error('Error fetching dashboard stats:', error);
    res.status(500).json({
      success: false,
      message: 'Failed to fetch dashboard statistics',
      error: error.message
    });
  }
});

// Error handling middleware
app.use((err, req, res, next) => {
  console.error('Unhandled error:', err);
  res.status(500).json({
    success: false,
    message: 'Internal server error',
    error: process.env.NODE_ENV === 'development' ? err.message : 'Something went wrong'
  });
});

// 404 handler
app.use((req, res) => {
  res.status(404).json({
    success: false,
    message: 'Route not found',
    path: req.path
  });
});

// Start server
app.listen(PORT, () => {
  console.log(`🚀 RFID Backend Server running on port ${PORT}`);
  console.log(`📊 Health check: http://localhost:${PORT}/health`);
  console.log(`📡 API base URL: http://localhost:${PORT}/api`);
});

module.exports = app;