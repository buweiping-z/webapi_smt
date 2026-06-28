using System.ComponentModel.DataAnnotations.Schema;

namespace webapi.Models
{
    public class InspectionTemplate
    {
        [Column("id")]
        public int Id { get; set; }

        [Column("device_model")]
        public string DeviceModel { get; set; } = string.Empty;

        [Column("item_name")]
        public string ItemName { get; set; } = string.Empty;

        [Column("item_type")]
        public string ItemType { get; set; } = string.Empty;

        [Column("unit")]
        public string? Unit { get; set; }  // ← 改为可为 null

        [Column("normal_min")]
        public decimal? NormalMin { get; set; }

        [Column("normal_max")]
        public decimal? NormalMax { get; set; }

        [Column("sort_order")]
        public int SortOrder { get; set; }

        [Column("is_mandatory")]
        public bool IsMandatory { get; set; } = true;

        [Column("frequency")]
        public string Frequency { get; set; } = "日";

        [Column("require_photo")]
        public bool RequirePhoto { get; set; } = false;

        // 定位照片集合（导航属性，映射为外键关系，无需 Column 注解）
        public List<InspectionPositionPhoto> PositionPhotos { get; set; } = new();
    }
}
